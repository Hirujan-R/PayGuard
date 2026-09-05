package com.payguard.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The simulated bank's own record of a settled charge. Written BEFORE the
 * response is returned to the gateway, so it survives even a dropped response.
 * This is the source of truth the reconciliation job queries.
 */
@Entity
@Table(name = "bank_ledger")
public class BankLedgerEntry {

    @Id
    private UUID id;

    @Column(name = "bank_request_id", nullable = false, unique = true)
    private String bankRequestId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    private String region;

    @Column(name = "charge_reference", nullable = false, unique = true)
    private String chargeReference;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(nullable = false)
    private boolean refunded;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (processedAt == null) processedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getBankRequestId() { return bankRequestId; }
    public void setBankRequestId(String bankRequestId) { this.bankRequestId = bankRequestId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getChargeReference() { return chargeReference; }
    public void setChargeReference(String chargeReference) { this.chargeReference = chargeReference; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public boolean isRefunded() { return refunded; }
    public void setRefunded(boolean refunded) { this.refunded = refunded; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
}
