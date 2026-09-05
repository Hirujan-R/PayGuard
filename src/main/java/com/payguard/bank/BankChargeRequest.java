package com.payguard.bank;

/**
 * A single charge attempt as sent to the bank. {@code bankRequestId} is the
 * gateway-generated idempotency key for THIS bank call (distinct from the
 * client-facing idempotency key) — it is how the gateway later finds the
 * charge in the bank ledger during reconciliation.
 */
public record BankChargeRequest(
        String bankRequestId,
        String accountId,
        long amountMinor,
        String currency,
        String region
) {}
