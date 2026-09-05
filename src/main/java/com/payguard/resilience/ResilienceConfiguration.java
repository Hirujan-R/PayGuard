package com.payguard.resilience;

import com.payguard.bank.BankException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j wiring for calls to the simulated bank.
 *
 * Ordering is deliberate (see BankCallExecutor): the CircuitBreaker guards EACH
 * individual attempt, and the Retry decorator sits OUTSIDE it. That way the
 * circuit measures the bank's per-attempt health and opens when real attempts
 * are failing, while the retry layer never wastes attempts once the circuit is
 * open (CallNotPermittedException is excluded from retries).
 *
 * Only BankException.Timeout / Unavailable count as circuit failures. A lost
 * response is NOT a bank failure - the bank actually settled the charge - so it
 * is explicitly ignored by both the breaker and the retry policy.
 */
@Configuration
public class ResilienceConfiguration {

    @Bean
    public CircuitBreaker bankCircuitBreaker(ResilienceProperties properties) {
        ResilienceProperties.CircuitBreaker cfg = properties.getCircuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(cfg.getSlidingWindowSize())
                .failureRateThreshold(cfg.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cfg.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cfg.getPermittedNumberOfCallsInHalfOpenState())
                .recordExceptions(BankException.Timeout.class, BankException.Unavailable.class)
                .ignoreExceptions(BankException.ResponseLost.class)
                .build();
        return CircuitBreaker.of("bank", config);
    }

    @Bean
    public Retry bankRetry(ResilienceProperties properties) {
        ResilienceProperties.Retry cfg = properties.getRetry();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(cfg.getMaxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(
                        cfg.getInitialBackoffMs(), cfg.getBackoffMultiplier()))
                .retryExceptions(BankException.Timeout.class, BankException.Unavailable.class)
                .ignoreExceptions(
                        BankException.ResponseLost.class,
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
                .build();
        return Retry.of("bank", config);
    }
}
