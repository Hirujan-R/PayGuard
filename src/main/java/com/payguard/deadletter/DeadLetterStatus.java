package com.payguard.deadletter;

public enum DeadLetterStatus {
    /** Awaiting an operator decision (or an automated retry). */
    OPEN,
    /** Successfully replayed and settled. */
    REPLAYED
}
