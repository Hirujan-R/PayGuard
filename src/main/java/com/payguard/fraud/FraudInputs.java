package com.payguard.fraud;

import java.time.Instant;

/**
 * Everything the fraud engine needs, assembled by FraudService from the
 * account's transaction history. Kept a plain record so the rules can be unit
 * tested with no database.
 */
public record FraudInputs(
        long amountMinor,
        String region,
        Instant referenceTime,
        int attemptsInWindow,
        java.util.List<Long> chargedAmounts,
        String lastRegion,
        Instant lastRegionAt
) {}
