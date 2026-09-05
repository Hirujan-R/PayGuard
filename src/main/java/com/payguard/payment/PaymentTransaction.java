package com.payguard.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Amount in the smallest currency unit (never float — money must be exact). */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    private String description;

    @Column(name = "ip_address")
    private String ipAddress;

    private String region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "fraud_score")
    private Double fraudScore;

    @Column(name = "fraud_reasons")
    private String fraudReasons;

    @Column(name = "bank_request_id")
    private String bankRequestId;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(nullable = false)
    private boolean refunded;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public String getFraudReasons() { return fraudReasons; }
    public void setFraudReasons(String fraudReasons) { this.fraudReasons = fraudReasons; }
    public String getBankRequestId() { return bankRequestId; }
    public void setBankRequestId(String bankRequestId) { this.bankRequestId = bankRequestId; }
    public String getBankReference() { return bankReference; }
    public void setBankReference(String bankReference) { this.bankReference = bankReference; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public boolean isRefunded() { return refunded; }
    public void setRefunded(boolean refunded) { this.refunded = refunded; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
