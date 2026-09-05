package com.payguard.fraud;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudEngineTest {

    private final FraudEngine engine = new FraudEngine(new FraudProperties());

    private FraudInputs inputs(long amount, String region, int attempts,
                               List<Long> history, String lastRegion, Instant lastRegionAt) {
        return new FraudInputs(amount, region, Instant.now(), attempts, history, lastRegion, lastRegionAt);
    }

    @Test
    void cleanTransactionWithinPersonalHistoryIsNotFlagged() {
        List<Long> history = List.of(1000L, 1000L, 1000L, 1000L);
        FraudAssessment a = engine.assess(inputs(1000, "UK", 0, history, "UK", Instant.now().minusSeconds(60)));
        assertFalse(a.flagged());
        assertEquals(0.0, a.score(), 1e-9);
        assertTrue(a.reasons().isEmpty());
    }

    @Test
    void amountOutsideHistoricalRangeIsFlaggedAsOutlier() {
        List<Long> history = List.of(900L, 1000L, 1100L, 1050L, 950L);
        FraudAssessment a = engine.assess(inputs(500_000, "UK", 0, history, "UK", Instant.now().minusSeconds(60)));
        assertTrue(a.flagged());
        assertTrue(a.reasons().stream().anyMatch(r -> r.startsWith("amount_outlier")));
    }

    @Test
    void insufficientHistoryYieldsNoAmountSignal() {
        FraudAssessment a = engine.assess(inputs(500_000, "UK", 0, List.of(1000L), "UK", Instant.now().minusSeconds(60)));
        assertFalse(a.flagged());
    }

    @Test
    void highAttemptCountWithinWindowTriggersVelocity() {
        FraudAssessment a = engine.assess(inputs(1000, "UK", 8, List.of(), "UK", Instant.now().minusSeconds(60)));
        assertTrue(a.flagged());
        assertTrue(a.reasons().stream().anyMatch(r -> r.startsWith("velocity")));
    }

    @Test
    void recentJumpBetweenRegionsTriggersGeoJump() {
        FraudAssessment a = engine.assess(inputs(1000, "US", 0, List.of(), "UK", Instant.now().minusSeconds(10 * 60)));
        assertTrue(a.flagged());
        assertTrue(a.reasons().stream().anyMatch(r -> r.startsWith("geo_jump")));
    }

    @Test
    void oldRegionActivityDoesNotTriggerGeoJump() {
        FraudAssessment a = engine.assess(inputs(1000, "US", 0, List.of(), "UK", Instant.now().minusSeconds(200 * 60)));
        assertFalse(a.flagged());
    }
}
