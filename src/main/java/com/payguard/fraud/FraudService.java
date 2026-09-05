package com.payguard.fraud;

import com.payguard.payment.PaymentStatus;
import com.payguard.payment.PaymentTransaction;
import com.payguard.payment.PaymentTransactionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads an account's recent transaction history from Postgres and feeds it to
 * the pure rules in {@link FraudEngine}.
 */
@Component
public class FraudService {

    private final FraudEngine engine;
    private final FraudProperties properties;
    private final PaymentTransactionRepository repository;

    public FraudService(FraudEngine engine,
                        FraudProperties properties,
                        PaymentTransactionRepository repository) {
        this.engine = engine;
        this.properties = properties;
        this.repository = repository;
    }

    public FraudAssessment assess(PaymentTransaction txn) {
        Instant reference = txn.getCreatedAt();
        Instant windowStart = reference.minusSeconds(properties.getVelocityWindowMinutes() * 60L);

        List<Long> chargedAmounts = new ArrayList<>();
        int attemptsInWindow = 0;
        String lastRegion = null;
        Instant lastRegionAt = null;

        for (Object[] row : repository.findFraudHistory(txn.getAccountId(), txn.getId())) {
            String region = (String) row[1];
            String status = (String) row[2];
            long amount = ((Number) row[3]).longValue();
            Instant at = toInstant(row[4]);

            if (at.isAfter(windowStart) && !at.isAfter(reference)) {
                attemptsInWindow++;
            }
            PaymentStatus parsed = PaymentStatus.valueOf(status);
            if (parsed == PaymentStatus.SUCCEEDED || parsed == PaymentStatus.UNKNOWN) {
                chargedAmounts.add(amount);
            }
            if (lastRegion == null) {
                lastRegion = region;
                lastRegionAt = at;
            }
        }

        FraudInputs inputs = new FraudInputs(
                txn.getAmountMinor(),
                txn.getRegion(),
                reference,
                attemptsInWindow,
                chargedAmounts,
                lastRegion,
                lastRegionAt
        );
        return engine.assess(inputs);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        throw new IllegalStateException("unexpected timestamp column type: " + value.getClass());
    }
}
