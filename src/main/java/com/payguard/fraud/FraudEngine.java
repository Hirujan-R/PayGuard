package com.payguard.fraud;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, fully explainable rules engine. Three layered signals, each of
 * which contributes a score in [0,1]; if the sum crosses the configured
 * threshold the transaction is routed to manual review instead of being
 * auto-processed. Layered signals, not a single verdict — the same philosophy
 * real fraud systems use.
 */
@Component
public class FraudEngine {

    private final FraudProperties properties;

    public FraudEngine(FraudProperties properties) {
        this.properties = properties;
    }

    public FraudAssessment assess(FraudInputs in) {
        List<String> reasons = new ArrayList<>();
        double score = 0.0;

        // 1) Velocity: too many attempts from this account in a short window.
        int attempts = in.attemptsInWindow();
        if (attempts >= properties.getVelocityHighAttempts()) {
            score += 0.7;
            reasons.add("velocity:" + attempts + " attempts within "
                    + properties.getVelocityWindowMinutes() + "m");
        } else if (attempts >= properties.getVelocityMinAttempts()) {
            score += 0.4;
            reasons.add("velocity:" + attempts + " attempts within "
                    + properties.getVelocityWindowMinutes() + "m");
        }

        // 2) Amount anomaly: z-score against the account's own charge history.
        double amountScore = amountAnomalyScore(in.amountMinor(), in.chargedAmounts(), reasons);
        score += amountScore;

        // 3) Geo-jump: previous activity in a different region within a short window.
        if (in.lastRegion() != null && !in.lastRegion().equals(in.region())
                && in.lastRegionAt() != null) {
            Duration since = Duration.between(in.lastRegionAt(), in.referenceTime());
            if (!since.isNegative()
                    && since.toMinutes() <= properties.getGeoJumpWindowMinutes()) {
                score += 0.8;
                reasons.add("geo_jump: " + in.lastRegion() + " -> " + in.region()
                        + " within " + since.toMinutes() + "m");
            }
        }

        return new FraudAssessment(Math.min(score, 2.0), reasons, score >= properties.getThreshold());
    }

    private double amountAnomalyScore(long amount, List<Long> history, List<String> reasons) {
        if (history.size() < properties.getAmountMinHistory()) {
            return 0.0; // not enough personal history to judge "normal"
        }
        double mean = history.stream().mapToDouble(Long::doubleValue).average().orElse(0.0);
        double variance = history.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum() / (history.size() - 1);
        double std = Math.sqrt(variance);
        if (std == 0.0) {
            if (Math.abs(amount - mean) <= 0) return 0.0;
            reasons.add("amount_outlier: no prior spread but amount deviates");
            return 0.8;
        }
        double z = Math.abs(amount - mean) / std;
        double contribution = 0.9 * Math.min(z / properties.getAmountZscale(), 1.0);
        if (contribution > 0.0) {
            reasons.add(String.format("amount_outlier: z=%.2f vs personal history", z));
        }
        return contribution;
    }
}
