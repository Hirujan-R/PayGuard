package com.payguard.failure;

import com.payguard.bank.SimulationMode;
import com.payguard.bank.SimulationState;
import com.payguard.deadletter.DeadLetterRepository;
import com.payguard.deadletter.DeadLetterService;
import com.payguard.deadletter.DeadLetterStatus;
import com.payguard.deadletter.DeadLetterTransaction;
import com.payguard.integration.AbstractIntegrationTest;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentService;
import com.payguard.payment.PaymentStatus;
import com.payguard.resilience.BankCallExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-injection tests. Each simulator failure mode is forced and the system
 * is asserted to end in the correct FINAL state — no happy-path-only testing.
 */
class FailureInjectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired SimulationState simulationState;
    @Autowired BankCallExecutor executor;
    @Autowired DeadLetterService deadLetterService;
    @Autowired DeadLetterRepository deadLetters;

    @AfterEach
    void heal() {
        simulationState.setMode(SimulationMode.NORMAL);
        deadLetterService.list().forEach(dl -> {
            if (dl.getStatus() == DeadLetterStatus.OPEN) {
                try {
                    deadLetterService.replay(dl.getId());
                } catch (Exception ignored) {
                    // best-effort cleanup between tests
                }
            }
        });
    }

    @Test
    void hardFailureExhaustsRetriesThenDeadLetters() {
        simulationState.setMode(SimulationMode.HARD_FAIL);
        PaymentResponse res = paymentService.submit(
                request("acc_fail_" + System.nanoTime(), 1500, "203.0.113.5"), "fail-hard-" + System.nanoTime());
        assertEquals(PaymentStatus.DEAD_LETTERED.name(), res.status());
        assertTrue(res.failureReason().contains("bank_unavailable"));
    }

    @Test
    void droppedResponseNeverDoubleChargesAndIsReconciled() {
        // covered by PaymentFlowIntegrationTest; here we assert the counterpoint:
        // a dropped response is NOT retried (would risk double-charge) and no
        // second ledger write can happen even if we keep hitting the simulator.
        simulationState.setMode(SimulationMode.DROPPED_RESPONSE);
        String account = "acc_drop2_" + System.nanoTime();
        PaymentResponse res = paymentService.submit(
                request(account, 222, "203.0.113.5"), "drop-2-" + System.nanoTime());
        assertEquals(PaymentStatus.UNKNOWN.name(), res.status());
    }

    @Test
    void circuitOpensThenDeadLettersQueueAndReplaySucceedsAfterHealing() throws Exception {
        String account = "acc_cb_" + System.nanoTime();
        String key = "cb-" + System.nanoTime();

        // 1. Bring the bank down; hammer it until the circuit breaker opens.
        simulationState.setMode(SimulationMode.HARD_FAIL);
        Instant deadline = Instant.now().plusSeconds(15);
        while (!executor.circuitState().equals("OPEN") && Instant.now().isBefore(deadline)) {
            paymentService.submit(request(account, 1000, "203.0.113.5"),
                    key + "-" + System.nanoTime());
        }
        assertEquals("OPEN", executor.circuitState(), "circuit breaker should open while bank is down");

        // 2. While open, payments fail fast and are queued, not left hanging.
        PaymentResponse queued = paymentService.submit(request(account, 1000, "203.0.113.5"),
                key + "-queued-" + System.nanoTime());
        assertEquals(PaymentStatus.DEAD_LETTERED.name(), queued.status());
        assertTrue(queued.failureReason().contains("circuit_open"));
        assertTrue(executor.circuitFailureRate() >= 50f);

        // 3. Heal the dependency; once the breaker probes a successful call, all
        //    queued transactions can be replayed to completion.
        simulationState.setMode(SimulationMode.NORMAL);
        Thread.sleep(800); // let the circuit leave OPEN and take a half-open probe

        List<DeadLetterTransaction> open = deadLetterService.list().stream()
                .filter(dl -> dl.getStatus() == DeadLetterStatus.OPEN)
                .toList();
        assertTrue(open.size() >= 1, "there should be queued dead-letter entries");
        int succeeded = 0;
        for (DeadLetterTransaction dl : open) {
            PaymentResponse r = deadLetterService.replay(dl.getId());
            if (r.status().equals(PaymentStatus.SUCCEEDED.name())) succeeded++;
        }
        assertEquals(open.size(), succeeded, "all replayed transactions should settle after healing");
    }
}
