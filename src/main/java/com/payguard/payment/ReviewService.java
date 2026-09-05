package com.payguard.payment;

import com.payguard.bank.BankChargeRequest;
import com.payguard.bank.BankChargeResult;
import com.payguard.bank.BankException;
import com.payguard.bank.BankSimulator;
import com.payguard.common.exception.ConflictException;
import com.payguard.common.exception.NotFoundException;
import com.payguard.deadletter.DeadLetterService;
import com.payguard.metrics.PaymentMetrics;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import com.payguard.resilience.BankCallExecutor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Human decision on a PENDING_REVIEW (fraud-flagged) transaction. The fraud
 * engine deliberately never called the bank, so the reviewer's verdict is the
 * last word before any money moves:
 *   approve -> charge the account now (bank may still fail, in which case the
 *              usual dead-letter / reconciliation paths take over)
 *   decline -> VOID the transaction; the bank is never contacted
 */
@Service
public class ReviewService {

    private final PaymentTransactionRepository repository;
    private final BankSimulator bankSimulator;
    private final BankCallExecutor bankCallExecutor;
    private final DeadLetterService deadLetterService;
    private final PaymentMetrics metrics;

    public ReviewService(PaymentTransactionRepository repository,
                         BankSimulator bankSimulator,
                         BankCallExecutor bankCallExecutor,
                         DeadLetterService deadLetterService,
                         PaymentMetrics metrics) {
        this.repository = repository;
        this.bankSimulator = bankSimulator;
        this.bankCallExecutor = bankCallExecutor;
        this.deadLetterService = deadLetterService;
        this.metrics = metrics;
    }

    @Transactional
    public PaymentResponse approve(UUID id) {
        PaymentTransaction txn = lockReviewable(id);

        // No bank request was ever allocated for a review-flagged payment.
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
            metrics.recordOutcome("succeeded");
        } catch (BankException.ResponseLost lost) {
            txn.setStatus(PaymentStatus.UNKNOWN);
            txn.setBankReference(lost.getChargeReference());
            txn.setFailureReason("approved but charge settled with response lost; awaiting reconciliation");
            metrics.recordOutcome("unknown");
        } catch (CallNotPermittedException e) {
            txn.setStatus(PaymentStatus.DEAD_LETTERED);
            txn.setFailureReason("approved but circuit_open: bank circuit breaker open, request queued");
            deadLetterService.enqueue(txn, txn.getFailureReason());
            metrics.recordOutcome("dead_lettered");
        } catch (BankException e) {
            txn.setStatus(PaymentStatus.DEAD_LETTERED);
            txn.setFailureReason("approved but " + e.getMessage());
            deadLetterService.enqueue(txn, txn.getFailureReason());
            metrics.recordOutcome("dead_lettered");
        }

        repository.save(txn);
        return PaymentResponse.from(txn);
    }

    @Transactional
    public PaymentResponse decline(UUID id) {
        PaymentTransaction txn = lockReviewable(id);
        txn.setStatus(PaymentStatus.VOIDED);
        txn.setFailureReason("declined after manual review");
        repository.save(txn);
        metrics.recordOutcome("declined");
        return PaymentResponse.from(txn);
    }

    private PaymentTransaction lockReviewable(UUID id) {
        PaymentTransaction txn = repository.findForUpdate(id)
                .orElseThrow(() -> new NotFoundException("payment not found: " + id));
        if (txn.getStatus() != PaymentStatus.PENDING_REVIEW) {
            throw new ConflictException("only PENDING_REVIEW payments can be reviewed (status="
                    + txn.getStatus() + ")");
        }
        return txn;
    }
}
