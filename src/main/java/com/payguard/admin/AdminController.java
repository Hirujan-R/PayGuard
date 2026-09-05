package com.payguard.admin;

import com.payguard.bank.SimulationMode;
import com.payguard.bank.SimulationState;
import com.payguard.deadletter.DeadLetterRepository;
import com.payguard.deadletter.DeadLetterService;
import com.payguard.deadletter.DeadLetterTransaction;
import com.payguard.fraud.RegionService;
import com.payguard.metrics.PaymentMetrics;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import com.payguard.reconciliation.ReconciliationRunner;
import com.payguard.resilience.BankCallExecutor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
/**
 * Demo + operator surface: recent-transaction feed, live simulator controls
 * (so you can watch the circuit breaker open), chaos injection, fraud-history
 * seeding and the dead-letter view.
 */
@RestController
@RequestMapping("/admin")
@Validated
public class AdminController {

    private final PaymentTransactionRepository payments;
    private final SimulationState simulationState;
    private final BankCallExecutor bankCallExecutor;
    private final PaymentMetrics metrics;
    private final DeadLetterRepository deadLetters;
    private final DeadLetterService deadLetterService;
    private final ReconciliationRunner reconciliationRunner;
    private final RegionService regionService;

    public AdminController(PaymentTransactionRepository payments,
                           SimulationState simulationState,
                           BankCallExecutor bankCallExecutor,
                           PaymentMetrics metrics,
                           DeadLetterRepository deadLetters,
                           DeadLetterService deadLetterService,
                           ReconciliationRunner reconciliationRunner,
                           RegionService regionService) {
        this.payments = payments;
        this.simulationState = simulationState;
        this.bankCallExecutor = bankCallExecutor;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.deadLetterService = deadLetterService;
        this.reconciliationRunner = reconciliationRunner;
        this.regionService = regionService;
    }

    // ------------------------------------------------------------------ feed

    @GetMapping("/transactions")
    public List<PaymentResponse> recentTransactions(@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return payments.findTop50ByOrderByCreatedAtDesc().stream()
                .limit(limit)
                .map(PaymentResponse::from)
                .toList();
    }

    @GetMapping("/dead-letter")
    public List<DeadLetterView> deadLetters() {
        return deadLetters.findAllByOrderByCreatedAtDesc().stream()
                .map(dl -> {
                    PaymentTransaction txn = payments.findById(dl.getTransactionId()).orElse(null);
                    return new DeadLetterView(
                            dl.getId().toString(),
                            dl.getTransactionId().toString(),
                            dl.getStatus().name(),
                            dl.getAttemptCount(),
                            dl.getLastError(),
                            dl.getCreatedAt(),
                            dl.getReplayedAt(),
                            txn == null ? null : PaymentResponse.from(txn));
                })
                .toList();
    }

    public record DeadLetterView(
            String id,
            String transactionId,
            String status,
            int attemptCount,
            String lastError,
            Instant createdAt,
            Instant replayedAt,
            PaymentResponse transaction
    ) {}

    /** Manually retry a dead-lettered transaction against the bank. */
    @PostMapping("/dead-letter/{id}/replay")
    public PaymentResponse replayDeadLetter(@PathVariable UUID id) {
        return deadLetterService.replay(id);
    }

    /** Trigger a reconciliation pass on demand (also runs on a timer). */
    @PostMapping("/reconciliations/run")
    public ReconciliationRunner.ReconciliationSummary runReconciliation() {
        return reconciliationRunner.run();
    }

    // ----------------------------------------------------- simulator controls

    /** Change the simulated bank's behaviour (NORMAL / HARD_FAIL / TIMEOUT / DROPPED_RESPONSE). */
    @PostMapping("/simulator/mode")
    public SimulationStateView setMode(@RequestParam SimulationMode mode) {
        simulationState.setMode(mode);
        return state();
    }

    /** Inject random failures at a given rate on top of the fixed mode. */
    @PostMapping("/simulator/chaos")
    public SimulationStateView setChaos(@RequestBody ChaosRequest request) {
        simulationState.setChaos(new SimulationState.ChaosProfile(request.enabled(), request.failureRate()));
        return state();
    }

    @GetMapping("/simulator/state")
    public SimulationStateView state() {
        SimulationState.ChaosProfile chaos = simulationState.getChaos();
        return new SimulationStateView(
                simulationState.getMode().name(),
                chaos.enabled(),
                chaos.failureRate(),
                bankCallExecutor.circuitState(),
                bankCallExecutor.circuitFailureRate(),
                deadLetters.count()
        );
    }

    public record ChaosRequest(boolean enabled, @Min(0) @Max(1) double failureRate) {}

    public record SimulationStateView(
            String mode,
            boolean chaosEnabled,
            double chaosRate,
            String circuitState,
            float circuitFailureRate,
            long deadLetterCount
    ) {}

    // ------------------------------------------------ fraud demo seeding

    /**
     * Create a realistic transaction history for an account so the fraud rules
     * have something to judge against (amount z-score / velocity). Rows are
     * written directly as already-SUCCEEDED and do NOT hit the bank simulator.
     */
    @PostMapping("/demo/seed-account")
    public void seedAccount(@RequestBody SeedAccountRequest request) {
        Random rng = new Random();
        for (int i = 1; i <= request.count(); i++) {
            PaymentTransaction txn = new PaymentTransaction();
            txn.setIdempotencyKey("seed_" + UUID.randomUUID());
            txn.setAccountId(request.accountId());
            txn.setAmountMinor(request.amountMin() + (long) (rng.nextDouble() * (request.amountMax() - request.amountMin())));
            txn.setCurrency("GBP");
            txn.setDescription("seeded history");
            txn.setIpAddress(request.regionIp() == null ? "203.0.113.4" : request.regionIp());
            txn.setRegion(regionService.lookup(txn.getIpAddress()));
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setBankRequestId("bkq_seed_" + UUID.randomUUID());
            txn.setBankReference("br_seed_" + UUID.randomUUID());
            // spread over the last `minutesBack` minutes so velocity/geo windows behave
            txn.setCreatedAt(Instant.now().minus((long) request.minutesBack() - i + 1, ChronoUnit.MINUTES));
            payments.save(txn);
            metrics.recordOutcome("seeded");
        }
    }

    public record SeedAccountRequest(
            String accountId,
            @Min(1) long amountMin,
            @Min(1) long amountMax,
            @Min(1) @Max(500) int count,
            String regionIp,
            @Min(1) int minutesBack
    ) {}
}
