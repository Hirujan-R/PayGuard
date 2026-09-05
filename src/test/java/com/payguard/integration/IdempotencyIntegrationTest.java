package com.payguard.integration;

import com.payguard.bank.BankLedgerRepository;
import com.payguard.bank.BankSimulator;
import com.payguard.bank.SimulationMode;
import com.payguard.bank.SimulationState;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentService;
import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Stripe-critical guarantee: a retried request must never produce two
 * charges. Because idempotency is implemented as a database-backed lock (unique
 * idempotency_key on the PENDING row), this holds even when two requests for
 * the same key arrive at the same time.
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired PaymentTransactionRepository payments;
    @Autowired BankLedgerRepository ledger;
    @Autowired BankSimulator bankSimulator;
    @Autowired SimulationState simulationState;

    @BeforeEach
    void healthy() {
        simulationState.setMode(SimulationMode.NORMAL);
    }

    @Test
    void duplicateKeyReturnsOriginalResultWithoutSecondCharge() {
        String account = "acc_dupeseq_" + System.nanoTime();
        String key = "idem-seq-" + System.nanoTime();

        PaymentResponse first = paymentService.submit(request(account, 2500, "203.0.113.5"), key);
        PaymentResponse second = paymentService.submit(request(account, 2500, "203.0.113.5"), key);

        assertEquals(PaymentStatus.SUCCEEDED.name(), first.status());
        assertEquals(first.id(), second.id());
        assertEquals(1, payments.findByIdempotencyKey(key).stream().count());
        assertEquals(1, ledger.findAll().stream().filter(e -> e.getAccountId().equals(account)).count());
    }

    @Test
    void concurrentRequestsForSameKeySettleExactlyOnce() throws Exception {
        String account = "acc_conc_" + System.nanoTime();
        String key = "idem-conc-" + System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<PaymentResponse> task = () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return paymentService.submit(request(account, 5000, "198.51.100.5"), key);
        };

        Future<PaymentResponse> f1 = pool.submit(task);
        Future<PaymentResponse> f2 = pool.submit(task);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        PaymentResponse r1 = f1.get(20, TimeUnit.SECONDS);
        PaymentResponse r2 = f2.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(r1.id(), r2.id());
        assertEquals(PaymentStatus.SUCCEEDED.name(), r1.status());
        assertEquals(1, payments.findAll().stream()
                .filter(p -> p.getIdempotencyKey().equals(key)).count());
        assertEquals(1, ledger.findAll().stream()
                .filter(e -> e.getAccountId().equals(account)).count());
    }

    @Test
    void distinctKeysProduceDistinctCharges() {
        String account = "acc_multi_" + System.nanoTime();
        PaymentResponse a = paymentService.submit(request(account, 100, "203.0.113.5"), "key-a-" + System.nanoTime());
        PaymentResponse b = paymentService.submit(request(account, 100, "203.0.113.5"), "key-b-" + System.nanoTime());
        assertNotEquals(a.id(), b.id());
        List<PaymentTransaction> txs = payments.findAll().stream()
                .filter(p -> p.getAccountId().equals(account))
                .toList();
        assertEquals(2, txs.size());
    }
}
