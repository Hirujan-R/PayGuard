package com.payguard.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the payment API. */
public final class PaymentDtos {

    private PaymentDtos() {}

    public static class CreatePaymentRequest {
        @NotBlank(message = "is required")
        private String accountId;

        /** Money is expressed in the smallest currency unit (e.g. pence) so it is exact. */
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be at least 1")
        private Long amountMinor;

        @NotBlank(message = "is required")
        @Size(min = 3, max = 3, message = "must be a 3-letter ISO currency code")
        private String currency;

        private String description;

        private String ipAddress;

        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        public Long getAmountMinor() { return amountMinor; }
        public void setAmountMinor(Long amountMinor) { this.amountMinor = amountMinor; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    }

    public record PaymentResponse(
            String id,
            String idempotencyKey,
            String accountId,
            long amountMinor,
            String currency,
            String description,
            String status,
            String bankReference,
            String failureReason,
            double fraudScore,
            List<String> fraudReasons,
            boolean refunded,
            Instant refundedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static PaymentResponse from(PaymentTransaction t) {
            return new PaymentResponse(
                    t.getId().toString(),
                    t.getIdempotencyKey(),
                    t.getAccountId(),
                    t.getAmountMinor(),
                    t.getCurrency(),
                    t.getDescription(),
                    t.getStatus().name(),
                    t.getBankReference(),
                    t.getFailureReason(),
                    t.getFraudScore() == null ? 0.0 : t.getFraudScore(),
                    reasons(t.getFraudReasons()),
                    t.isRefunded(),
                    t.getRefundedAt(),
                    t.getCreatedAt(),
                    t.getUpdatedAt()
            );
        }

        private static List<String> reasons(String raw) {
            if (raw == null || raw.isBlank()) return Collections.emptyList();
            return Arrays.asList(raw.split("\\s*;\\s*"));
        }
    }

    public record IdResponse(UUID id) {}
}
