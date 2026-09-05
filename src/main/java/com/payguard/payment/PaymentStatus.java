package com.payguard.payment;

/**
 * Lifecycle of a payment as seen by the gateway.
 *
 * PENDING is a transient marker used while the idempotency key is being
 * processed (it doubles as a database-backed lock: the unique constraint on
 * {@code idempotency_key} means exactly one request ever gets past PENDING).
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    PENDING_REVIEW,
    UNKNOWN,
    DEAD_LETTERED
}
