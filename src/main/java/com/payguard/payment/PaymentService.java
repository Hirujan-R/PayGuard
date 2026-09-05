package com.payguard.payment;

import com.payguard.bank.BankChargeRequest;
import com.payguard.bank.BankChargeResult;
import com.payguard.bank.BankException;
import com.payguard.bank.BankLedgerRepository;
import com.payguard.bank.BankSimulator;
import com.payguard.common.exception.BadRequestException;
import com.payguard.common.exception.ConflictException;
import com.payguard.common.exception.NotFoundException;
import com.payguard.deadletter.DeadLetterService;
import com.payguard.fraud.FraudAssessment;
import com.payguard.fraud.FraudService;
import com.payguard.fraud.RegionService;
import com.payguard.metrics.PaymentMetrics;
import com.payguard.payment.PaymentDtos.CreatePaymentRequest;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.resilience.BankCallExecutor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Core payment orchestration. Responsibilities in order:
 *  1. Idempotency: allocate a PENDING transaction under the client key. The
 *     unique constraint on idempotency_key makes this a database-backed lock —
 *     exactly one concurrent request gets past it; the rest replay the stored
 *     result once the winner finishes.
 *  2. Fraud screen (rules engine).
 *  3. Resilience-wrapped call to the simulated bank (circuit breaker + retry).
 *  4. Persist the terminal state and update metrics.
 */
@Service
public class PaymentService {

    private static final long DUPLICATE_WAIT_MS = 200;
    private static final int DUPLICATE_MAX_WAITS = 50;

    private final PaymentTransactionRepository repository;
    private final RegionService regionService;
    private final FraudService fraudService;
    private final BankSimulator bankSimulator;
    private final BankCallExecutor bankCallExecutor;
    private final DeadLetterService deadLetterService;
    private final BankLedgerRepository bankLedgerRepository;
    private final PaymentMetrics metrics;

    public PaymentService(PaymentTransactionRepository repository,
                          RegionService regionService,
                          FraudService fraudService,
                          BankSimulator bankSimulator,
                          BankCallExecutor bankCallExecutor,
                          DeadLetterService deadLetterService,
                          BankLedgerRepository bankLedgerRepository,
                          PaymentMetrics metrics) {
        this.repository = repository;
        this.regionService = regionService;
        this.fraudService = fraudService;
        this.bankSimulator = bankSimulator;
        this.bankCallExecutor = bankCallExecutor;
        this.deadLetterService = deadLetterService;
        this.bankLedgerRepository = bankLedgerRepository;
        this.metrics = metrics;
    }

    public PaymentResponse submit(CreatePaymentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }
        Timer.Sample timer = metrics.startTimer();
        try {
            PaymentTransaction txn;
            try {
                txn = allocate(request, idempotencyKey);
            } catch (DataIntegrityViolationException duplicate) {
                return awaitDuplicate(idempotencyKey, request);
            }
            return finish(txn);
        } finally {
            metrics.stopTimer(timer);
        }
    }

    /**
     * The idempotency lock. Persisting the PENDING row first (in its own
     * committed transaction) means the unique key is held before any real work
     * happens, so a racing retry can never start a second charge.
     */
    private PaymentTransaction allocate(CreatePaymentRequest request, String idempotencyKey) {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setIdempotencyKey(idempotencyKey);
        txn.setAccountId(request.getAccountId());
        txn.setAmountMinor(request.getAmountMinor());
        txn.setCurrency(request.getCurrency().toUpperCase());
        txn.setDescription(request.getDescription());
        txn.setIpAddress(request.getIpAddress());
        txn.setRegion(regionService.lookup(request.getIpAddress()));
        txn.setStatus(PaymentStatus.PENDING);
        return repository.saveAndFlush(txn);
    }

    private PaymentResponse awaitDuplicate(String idempotencyKey, CreatePaymentRequest request) {
        for (int i = 0; i < DUPLICATE_MAX_WAITS; i++) {
            PaymentTransaction existing = repository.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (existing != null && existing.getStatus() != PaymentStatus.PENDING) {
                metrics.recordOutcome("duplicate");
                return PaymentResponse.from(existing);
            }
            sleep(DUPLICATE_WAIT_MS);
        }
        throw new ConflictException("request with this idempotency key is still in progress; retry shortly");
    }

    private PaymentResponse finish(PaymentTransaction txn) {
        FraudAssessment fraud = fraudService.assess(txn);
        if (fraud.flagged()) {
            txn.setStatus(PaymentStatus.PENDING_REVIEW);
            txn.setFraudScore(fraud.score());
            txn.setFraudReasons(String.join("; ", fraud.reasons()));
            txn.setFailureReason("flagged for manual review");
            repository.save(txn);
            metrics.recordOutcome("pending_review");
            return PaymentResponse.from(txn);
        }

        String bankRequestId = "bkq_" + UUID.randomUUID();
        txn.setBankRequestId(bankRequestId);
        repository.save(txn);

        try {
            BankChargeResult result = bankCallExecutor.execute(() ->
                    bankSimulator.attemptCharge(new BankChargeRequest(
                            bankRequestId,
                            txn.getAccountId(),
                            txn.getAmountMinor(),
                            txn.getCurrency(),
                            txn.getRegion())));
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setBankReference(result.chargeReference());
            txn.setFailureReason(null);
        } catch (BankException.ResponseLost lost) {
            // The bank settled the charge on its ledger but the reply never
            // reached us. We do NOT retry (that could double-charge). Marking
            // UNKNOWN lets the reconciliation job settle it against the ledger.
            txn.setStatus(PaymentStatus.UNKNOWN);
            txn.setBankReference(lost.getChargeReference());
            txn.setFailureReason("charge settled but response lost; awaiting reconciliation");
        } catch (CallNotPermittedException e) {
            deadLetter(txn, "circuit_open: bank circuit breaker open, request queued");
        } catch (BankException.Unavailable e) {
            deadLetter(txn, "bank_unavailable after retries: " + e.getMessage());
        } catch (BankException.Timeout e) {
            deadLetter(txn, "bank_timeout after retries: " + e.getMessage());
        } catch (Exception e) {
            txn.setStatus(PaymentStatus.FAILED);
            txn.setFailureReason(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            metrics.recordOutcome("failed");
        }

        repository.save(txn);
        if (txn.getStatus() == PaymentStatus.SUCCEEDED) metrics.recordOutcome("succeeded");
        if (txn.getStatus() == PaymentStatus.UNKNOWN) metrics.recordOutcome("unknown");
        return PaymentResponse.from(txn);
    }

    private void deadLetter(PaymentTransaction txn, String reason) {
        txn.setStatus(PaymentStatus.DEAD_LETTERED);
        txn.setFailureReason(reason);
        deadLetterService.enqueue(txn, reason);
        metrics.recordOutcome("dead_lettered");
    }

    public PaymentResponse get(String id) {
        return PaymentResponse.from(find(id));
    }

    @Transactional
    public PaymentResponse refund(String id) {
        PaymentTransaction txn = repository.findForUpdate(UUID.fromString(id))
                .orElseThrow(() -> new NotFoundException("payment not found: " + id));
        if (txn.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("only SUCCEEDED payments can be refunded (status=" + txn.getStatus() + ")");
        }
        if (txn.isRefunded()) {
            throw new ConflictException("payment " + id + " has already been refunded");
        }
        txn.setRefunded(true);
        txn.setRefundedAt(Instant.now());
        repository.save(txn);

        // Mirror the refund on the bank's own ledger for auditability.
        if (txn.getBankRequestId() != null) {
            bankLedgerRepository.findByBankRequestId(txn.getBankRequestId()).ifPresent(ledger -> {
                ledger.setRefunded(true);
                ledger.setRefundedAt(Instant.now());
                bankLedgerRepository.save(ledger);
            });
        }
        metrics.recordOutcome("refunded");
        return PaymentResponse.from(txn);
    }

    private PaymentTransaction find(String id) {
        try {
            return repository.findById(UUID.fromString(id))
                    .orElseThrow(() -> new NotFoundException("payment not found: " + id));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("payment not found: " + id);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
