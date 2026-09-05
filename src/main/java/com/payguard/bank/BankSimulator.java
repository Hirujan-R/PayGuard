package com.payguard.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The simulated downstream acquirer. Each charge is settled against the bank's
 * OWN ledger table (bank_ledger) BEFORE the response is produced, exactly like a
 * real payment processor's internal accounting. The four failure modes then
 * decide how that settled charge is communicated back to the gateway:
 *
 *  NORMAL          -> settled + response returned
 *  HARD_FAIL       -> nothing settled, transient error thrown
 *  TIMEOUT         -> nothing settled, deadline exceeded thrown
 *  DROPPED_RESPONSE-> charge IS settled on the ledger, but no response returns
 *
 * Because charges are idempotent on bankRequestId, a replayed request can never
 * settle twice — the ledger row already exists and the "new" attempt returns it.
 */
@Component
public class BankSimulator {

    private static final Logger log = LoggerFactory.getLogger(BankSimulator.class);

    private final BankLedgerRepository ledgerRepository;
    private final SimulationState state;

    public BankSimulator(BankLedgerRepository ledgerRepository, SimulationState state) {
        this.ledgerRepository = ledgerRepository;
        this.state = state;
    }

    /**
     * A settle is persisted to the bank ledger in its OWN committed transaction
     * (no @Transactional here — each repository write commits immediately).
     * This matters for DROPPED_RESPONSE: like a real acquirer, the bank records
     * the charge and only THEN discovers it cannot reach the gateway, so the
     * ledger write must survive the exception that follows.
     */
    public BankChargeResult attemptCharge(BankChargeRequest request) {
        SimulationMode behaviour = state.nextBehaviour();
        log.debug("bank attempt {} -> behaviour {}", request.bankRequestId(), behaviour);

        // Simulated network + processing latency (kept tiny so tests stay fast).
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Replaying an already-settled request must be a no-op returning the same charge.
        BankLedgerEntry existing = ledgerRepository.findByBankRequestId(request.bankRequestId()).orElse(null);
        if (existing != null) {
            return new BankChargeResult(existing.getChargeReference(), existing.getProcessedAt());
        }

        switch (behaviour) {
            case HARD_FAIL -> {
                throw new BankException.Unavailable("acquirer unavailable (simulated hard failure)");
            }
            case TIMEOUT -> {
                throw new BankException.Timeout("request exceeded the acquirer deadline (simulated timeout)");
            }
            case DROPPED_RESPONSE -> {
                BankLedgerEntry settled = settle(request);
                log.warn("bank request {} SETTLED but response lost", request.bankRequestId());
                throw new BankException.ResponseLost(
                        "charge settled but response lost (simulated)",
                        settled.getChargeReference());
            }
            case NORMAL -> {
                BankLedgerEntry settled = settle(request);
                return new BankChargeResult(settled.getChargeReference(), settled.getProcessedAt());
            }
            default -> throw new IllegalStateException("unknown simulation mode " + behaviour);
        }
    }

    private BankLedgerEntry settle(BankChargeRequest request) {
        BankLedgerEntry entry = new BankLedgerEntry();
        entry.setBankRequestId(request.bankRequestId());
        entry.setAccountId(request.accountId());
        entry.setAmountMinor(request.amountMinor());
        entry.setCurrency(request.currency());
        entry.setRegion(request.region());
        entry.setChargeReference("br_" + UUID.randomUUID());
        return ledgerRepository.save(entry);
    }
}
