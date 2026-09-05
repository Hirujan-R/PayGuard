package com.payguard.integration;

import com.payguard.bank.BankLedgerRepository;
import com.payguard.common.exception.ConflictException;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentService;
import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import com.payguard.payment.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules engine must be able to explain and route suspicious activity to
 * PENDING_REVIEW without ever calling the bank. The bank ledger is the source
 * of truth that proves no money moved for a flagged transaction.
 */
class FraudFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired PaymentTransactionRepository payments;
    @Autowired BankLedgerRepository ledger;
    @Autowired ReviewService reviewService;

    @Test
    void approvedReviewChargesTheAccountOnce() {
        String account = "acc_appr_" + System.nanoTime();
        seedHistory(account, 6, 900, 1100, 16);

        PaymentResponse flagged = paymentService.submit(
                request(account, 500_000, "203.0.113.5"), "review-approve-" + System.nanoTime());
        assertEquals(PaymentStatus.PENDING_REVIEW.name(), flagged.status());

        PaymentResponse approved = reviewService.approve(java.util.UUID.fromString(flagged.id()));
        assertEquals(PaymentStatus.SUCCEEDED.name(), approved.status());
        assertTrue(approved.bankReference().startsWith("br_"));
        assertEquals(1, ledger.findAll().stream()
                .filter(e -> e.getAccountId().equals(account)).count());
    }

    @Test
    void declinedReviewVoidsThePaymentAndNeverContactsTheBank() {
        String account = "acc_decl_" + System.nanoTime();
        seedHistory(account, 6, 900, 1100, 16);

        PaymentResponse flagged = paymentService.submit(
                request(account, 500_000, "203.0.113.5"), "review-decline-" + System.nanoTime());
        assertEquals(PaymentStatus.PENDING_REVIEW.name(), flagged.status());

        PaymentResponse declined = reviewService.decline(java.util.UUID.fromString(flagged.id()));
        assertEquals(PaymentStatus.VOIDED.name(), declined.status());
        assertEquals(0, ledger.findAll().stream()
                .filter(e -> e.getAccountId().equals(account)).count());
    }

    @Test
    void reviewOfNonReviewablePaymentIsRejected() {
        PaymentResponse ok = paymentService.submit(
                request("acc_rev_" + System.nanoTime(), 500, "203.0.113.5"), "review-guard-" + System.nanoTime());
        assertEquals(PaymentStatus.SUCCEEDED.name(), ok.status());
        assertThrows(ConflictException.class,
                () -> reviewService.decline(java.util.UUID.fromString(ok.id())));
    }

    @Test
    void amountOutsidePersonalHistoryGoesToReviewAndBankIsNeverCalled() {
        String account = "acc_amt_" + System.nanoTime();
        seedHistory(account, 6, 900, 1100, 16);

        PaymentResponse flagged = paymentService.submit(
                request(account, 500_000, "203.0.113.5"), "amt-" + System.nanoTime());

        assertEquals(PaymentStatus.PENDING_REVIEW.name(), flagged.status());
        assertTrue(flagged.fraudScore() >= 0.7);
        assertTrue(flagged.fraudReasons().stream().anyMatch(r -> r.contains("amount_outlier")));
        // no money moved: the bank ledger has nothing for this account
        assertEquals(0, ledger.findAll().stream()
                .filter(e -> e.getAccountId().equals(account)).count());
    }

    @Test
    void velocityRuleFlagsBurstOfAttempts() {
        String account = "acc_vel_" + System.nanoTime();

        for (int i = 1; i <= 8; i++) {
            PaymentResponse ok = paymentService.submit(request(account, 500, "203.0.113.5"),
                    "vel-" + System.nanoTime());
            assertEquals(PaymentStatus.SUCCEEDED.name(), ok.status(), "early attempts should pass");
        }

        PaymentResponse flagged = paymentService.submit(request(account, 500, "203.0.113.5"),
                "vel-flag-" + System.nanoTime());
        assertEquals(PaymentStatus.PENDING_REVIEW.name(), flagged.status());
        assertTrue(flagged.fraudReasons().stream().anyMatch(r -> r.startsWith("velocity")));

        // exactly 8 charges reached the bank for this account
        assertEquals(8, ledger.findAll().stream()
                .filter(e -> e.getAccountId().equals(account)).count());
        assertFalse(payments.findAll().stream()
                .filter(p -> p.getAccountId().equals(account))
                .allMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED));
    }

    private void seedHistory(String account, int count, long min, long max, int minutesBackStart) {
        Random rng = new Random();
        for (int i = 1; i <= count; i++) {
            PaymentTransaction txn = new PaymentTransaction();
            txn.setIdempotencyKey("seed_" + UUID.randomUUID());
            txn.setAccountId(account);
            txn.setAmountMinor(min + (long) (rng.nextDouble() * (max - min)));
            txn.setCurrency("GBP");
            txn.setIpAddress("203.0.113.4");
            txn.setRegion("UK");
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setBankRequestId("bkq_" + UUID.randomUUID());
            txn.setBankReference("br_" + UUID.randomUUID());
            // timestamps older than the velocity window so only the amount rule fires
            txn.setCreatedAt(Instant.now().minus(minutesBackStart + i, ChronoUnit.MINUTES));
            payments.save(txn);
        }
    }
}
