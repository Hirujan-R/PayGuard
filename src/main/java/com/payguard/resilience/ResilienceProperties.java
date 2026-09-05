package com.payguard.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payguard.resilience")
public class ResilienceProperties {

    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Retry retry = new Retry();

    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public static class CircuitBreaker {
        private int slidingWindowSize = 10;
        private int failureRateThreshold = 40;
        private int waitDurationInOpenStateMs = 10_000;
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }
        public int getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(int failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        public int getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(int waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }
        public int getPermittedNumberOfCallsInHalfOpenState() { return permittedNumberOfCallsInHalfOpenState; }
        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private int initialBackoffMs = 200;
        private double backoffMultiplier = 2.0;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(int initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    }
}
