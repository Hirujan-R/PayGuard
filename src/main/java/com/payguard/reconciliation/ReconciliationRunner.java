package com.payguard.reconciliation;

import com.payguard.bank.BankLedgerEntry;
import com.payguard.bank.BankLedgerRepository;
import com.payguard.deadletter.DeadLetterService;
import com.payguard.metrics.PaymentMetrics;
import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The reconciliation job — PayGuard's answer to the hardest payments problem:
 * "the gateway never heard back, so does the customer owe money or not?"
 *
 * When a transaction is UNKNOWN the charge MAY have settled at the bank (its
 * ledger is authoritative). After a short grace period this job asks the bank's
 * ledger whether the charge exists for the bankRequestId:
 *   found    -> it settled, so mark SUCCEEDED (the money did move)
 *   not found-> it never settled, so mark FAILED (the customer was not charged)
 *
 * Orphaned PENDING rows (e.g. a crash between allocating the idempotency key
 * and finishing the request) are dead-lettered so nothing silently stalls.
 */
@Component
public class ReconciliationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    private final PaymentTransactionRepository payments;
    private final BankLedgerRepository ledger;
    private final DeadLetterService deadLetterService;
    private final PaymentMetrics metrics;
    private final ReconcileProperties properties;

    public ReconciliationRunner(PaymentTransactionRepository payments,
                                BankLedgerRepository ledger,
                                DeadLetterService deadLetterService,
                                PaymentMetrics metrics,
                                ReconcileProperties properties) {
        this.payments = payments;
        this.ledger = ledger;
        this.deadLetterService = deadLetterService;
        this.metrics = metrics;
        this.properties = properties;
    }

    public ReconciliationSummary run() {
        int unknown = 0, matched = 0, notFound = 0, orphaned = 0;

        Instant now = Instant.now();
        Instant unknownCutoff = now.minusMillis(properties.getUnknownGraceMs());
        Instant pendingCutoff = now.minusMillis(properties.getPendingGraceMs());

        for (PaymentTransaction txn : candidates()) {
            if (txn.getStatus() == PaymentStatus.UNKNOWN && !txn.getUpdatedAt().isAfter(unknownCutoff)) {
                unknown++;
                boolean found = false;
                if (txn.getBankRequestId() != null) {
                    BankLedgerEntry entry = ledger.findByBankRequestId(txn.getBankRequestId()).orElse(null);
                    if (entry != null) {
                        found = true;
                        txn.setStatus(PaymentStatus.SUCCEEDED);
                        txn.setBankReference(entry.getChargeReference());
                        txn.setFailureReason(null);
                        log.info("reconciliation: payment {} SETTLED (ref {})", txn.getId(), entry.getChargeReference());
                    }
                }
                if (found) {
                    matched++;
                    metrics.recordOutcome("resolved_settled");
                } else {
                    notFound++;
                    txn.setStatus(PaymentStatus.FAILED);
                    txn.setFailureReason("not found on bank ledger during reconciliation");
                    log.warn("reconciliation: payment {} NOT settled", txn.getId());
                    metrics.recordOutcome("resolved_not_settled");
                }
                payments.save(txn);
            } else if (txn.getStatus() == PaymentStatus.PENDING && !txn.getUpdatedAt().isAfter(pendingCutoff)) {
                orphaned++;
                txn.setStatus(PaymentStatus.DEAD_LETTERED);
                txn.setFailureReason("orphaned pending (process died mid-request)");
                payments.save(txn);
                deadLetterService.enqueue(txn, "orphaned pending transaction");
                metrics.recordOutcome("orphaned");
            }
        }
        return new ReconciliationSummary(unknown, matched, notFound, orphaned);
    }

    private List<PaymentTransaction> candidates() {
        // Small dataset: scan candidate rows via the status index rather than
        // maintaining a second query layer for the two statuses of interest.
        return payments.findAll().stream()
                .filter(t -> t.getStatus() == PaymentStatus.UNKNOWN || t.getStatus() == PaymentStatus.PENDING)
                .toList();
    }

    public record ReconciliationSummary(int unknown, int matched, int notFound, int orphaned) {}
}
