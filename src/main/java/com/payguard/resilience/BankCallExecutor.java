package com.payguard.resilience;

import com.payguard.bank.BankChargeResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Explicit resilience chain around a single charge attempt.
 *
 * Decorator order (retry OUTSIDE circuit breaker) means:
 *   1. The circuit breaker tracks the bank's per-attempt health and opens when
 *      the configured failure rate is exceeded.
 *   2. While open, CallNotPermittedException surfaces immediately (no retry
 *      wastage) and the payment service routes the transaction to the
 *      dead-letter queue.
 *   3. While closed, retries with exponential backoff absorb transient timeouts
 *      and acquirer unavailability before the gateway reports failure.
 */
@Component
public class BankCallExecutor {

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public BankCallExecutor(CircuitBreaker circuitBreaker, Retry retry) {
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    public BankChargeResult execute(Supplier<BankChargeResult> bankCall) {
        Supplier<BankChargeResult> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, bankCall);
        Supplier<BankChargeResult> withRetries = Retry.decorateSupplier(retry, guarded);
        return withRetries.get();
    }

    public String circuitState() {
        return circuitBreaker.getState().name();
    }

    public float circuitFailureRate() {
        return circuitBreaker.getMetrics().getFailureRate();
    }
}
