package com.payguard.deadletter;

import com.payguard.bank.BankChargeRequest;
import com.payguard.bank.BankChargeResult;
import com.payguard.bank.BankException;
import com.payguard.bank.BankSimulator;
import com.payguard.common.exception.ConflictException;
import com.payguard.common.exception.NotFoundException;
import com.payguard.metrics.PaymentMetrics;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import com.payguard.resilience.BankCallExecutor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dead-letter queue handling. enqueue() is called by the payment service when a
 * transaction cannot be completed; replay() re-submits a dead-lettered charge
 * to the bank once the operator (or a healed dependency) is ready.
 */
@Service
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final BankSimulator bankSimulator;
    private final BankCallExecutor bankCallExecutor;
    private final PaymentMetrics metrics;

    public DeadLetterService(DeadLetterRepository deadLetterRepository,
                             PaymentTransactionRepository paymentRepository,
                             BankSimulator bankSimulator,
                             BankCallExecutor bankCallExecutor,
                             PaymentMetrics metrics) {
        this.deadLetterRepository = deadLetterRepository;
        this.paymentRepository = paymentRepository;
        this.bankSimulator = bankSimulator;
        this.bankCallExecutor = bankCallExecutor;
        this.metrics = metrics;
    }

    public void enqueue(PaymentTransaction txn, String error) {
        DeadLetterTransaction entry = deadLetterRepository
                .findByTransactionId(txn.getId())
                .orElseGet(DeadLetterTransaction::new);
        entry.setTransactionId(txn.getId());
        entry.setStatus(DeadLetterStatus.OPEN);
        entry.setAttemptCount(entry.getAttemptCount() + 1);
        entry.setLastError(error);
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(Instant.now());
        }
        deadLetterRepository.save(entry);
    }

    public List<DeadLetterTransaction> list() {
        return deadLetterRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public PaymentResponse replay(UUID deadLetterId) {
        DeadLetterTransaction dl = deadLetterRepository.findForUpdate(deadLetterId)
                .orElseThrow(() -> new NotFoundException("dead-letter entry not found: " + deadLetterId));
        if (dl.getStatus() == DeadLetterStatus.REPLAYED) {
            throw new ConflictException("dead-letter entry " + deadLetterId + " was already replayed");
        }

        PaymentTransaction txn = paymentRepository.findForUpdate(dl.getTransactionId())
                .orElseThrow(() -> new NotFoundException("payment not found: " + dl.getTransactionId()));
        if (txn.getStatus() != PaymentStatus.DEAD_LETTERED) {
            throw new ConflictException("payment is not dead-lettered (status=" + txn.getStatus() + ")");
        }

        dl.setAttemptCount(dl.getAttemptCount() + 1);
        dl.setLastError(null);

        // If the transaction was dead-lettered before a bank request id was ever
        // allocated (e.g. an orphaned PENDING cleaned up by reconciliation), mint
        // one now so the replay is still idempotent at the bank.
        if (txn.getBankRequestId() == null) {
            txn.setBankRequestId("bkq_" + UUID.randomUUID());
        }

        try {
            // Reusing the original bankRequestId keeps the call idempotent at the
            // bank: if a previous attempt actually settled, this returns that charge.
            BankChargeResult result = bankCallExecutor.execute(() ->
                    bankSimulator.attemptCharge(new BankChargeRequest(
                            txn.getBankRequestId(),
                            txn.getAccountId(),
                            txn.getAmountMinor(),
                            txn.getCurrency(),
                            txn.getRegion())));
            txn.setStatus(PaymentStatus.SUCCEEDED);
            txn.setBankReference(result.chargeReference());
            txn.setFailureReason(null);
            dl.setStatus(DeadLetterStatus.REPLAYED);
            dl.setReplayedAt(Instant.now());
            metrics.recordOutcome("replayed");
        } catch (CallNotPermittedException e) {
            dl.setLastError("circuit_open during replay; dependency still unhealthy");
        } catch (BankException e) {
            dl.setLastError("bank failure during replay: " + e.getMessage());
        }

        paymentRepository.save(txn);
        deadLetterRepository.save(dl);
        return PaymentResponse.from(txn);
    }
}
