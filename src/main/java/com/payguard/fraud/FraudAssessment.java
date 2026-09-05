package com.payguard.fraud;

import java.util.List;

/**
 * Explainable fraud verdict: a score, the human-readable rules that fired, and
 * whether the sum of the rule signals crossed the PENDING_REVIEW threshold.
 * Every number here can be explained in an interview — by design there is no
 * black-box model.
 */
public record FraudAssessment(double score, List<String> reasons, boolean flagged) {}
