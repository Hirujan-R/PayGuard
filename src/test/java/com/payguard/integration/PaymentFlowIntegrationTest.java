package com.payguard.integration;

import com.payguard.bank.SimulationMode;
import com.payguard.bank.SimulationState;
import com.payguard.common.exception.ConflictException;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentService;
import com.payguard.payment.PaymentStatus;
import com.payguard.reconciliation.ReconciliationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Happy path, refund correctness and the flagship failure case: the bank
 * settles a charge but the response is lost. This must end as SUCCEEDED (money
 * moved) once reconciliation confirms the charge on the bank's own ledger —
 * never as a mystery and never as a silent double charge.
 */
class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired SimulationState simulationState;
    @Autowired ReconciliationRunner reconciliation;

    @BeforeEach
    void healthy() {
        simulationState.setMode(SimulationMode.NORMAL);
    }

    @Test
    void successfulChargeReturnsBankReference() {
        PaymentResponse res = paymentService.submit(
                request("acc_ok_" + System.nanoTime(), 4200, "203.0.113.5"), "flow-ok-" + System.nanoTime());
        assertEquals(PaymentStatus.SUCCEEDED.name(), res.status());
        assertTrue(res.bankReference().startsWith("br_"));
    }

    @Test
    void refundIsAllowedOnceOnly() {
        String account = "acc_ref_" + System.nanoTime();
        PaymentResponse paid = paymentService.submit(request(account, 3000, "203.0.113.5"), "refund-1-" + System.nanoTime());
        assertEquals(PaymentStatus.SUCCEEDED.name(), paid.status());

        PaymentResponse refunded = paymentService.refund(paid.id());
        assertTrue(refunded.refunded());

        assertThrows(ConflictException.class, () -> paymentService.refund(paid.id()));
    }

    @Test
    void refundOfNonSucceededPaymentIsRejected() {
        simulationState.setMode(SimulationMode.HARD_FAIL);
        String account = "acc_no_" + System.nanoTime();
        PaymentResponse dead = paymentService.submit(
                request(account, 3000, "203.0.113.5"), "refund-guard-" + System.nanoTime());
        assertEquals(PaymentStatus.DEAD_LETTERED.name(), dead.status());

        simulationState.setMode(SimulationMode.NORMAL);
        assertThrows(ConflictException.class, () -> paymentService.refund(dead.id()));
    }

    @Test
    void lostResponseIsResolvedToSucceededByReconciliation() throws Exception {
        String account = "acc_lost_" + System.nanoTime();
        String key = "flow-lost-" + System.nanoTime();

        simulationState.setMode(SimulationMode.DROPPED_RESPONSE);
        PaymentResponse unknown = paymentService.submit(request(account, 777, "198.51.100.5"), key);
        assertEquals(PaymentStatus.UNKNOWN.name(), unknown.status());

        // reconciliation runs after a grace period in production; call it directly here
        ReconciliationRunner.ReconciliationSummary summary = reconciliation.run();
        assertTrue(summary.matched() >= 1);

        PaymentResponse settled = paymentService.get(unknown.id());
        assertEquals(PaymentStatus.SUCCEEDED.name(), settled.status());
    }
}
