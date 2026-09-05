package com.payguard.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Micrometer metrics, exposed in Prometheus format via Actuator
 * (/actuator/prometheus). Counters are tagged by outcome so a dashboard can
 * chart e.g. the dead-letter rate rising as the circuit opens.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> outcomeCounters = new ConcurrentHashMap<>();
    private final Timer durationTimer;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.durationTimer = Timer.builder("payguard.payments.duration")
                .description("End-to-end payment request processing time")
                .register(registry);
    }

    public void recordOutcome(String outcome) {
        outcomeCounters.computeIfAbsent(outcome, key ->
                Counter.builder("payguard.payments.total")
                        .tag("outcome", key)
                        .description("Payments by terminal outcome")
                        .register(registry))
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(durationTimer);
    }
}
