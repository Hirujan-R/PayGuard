package com.payguard.bank;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable knobs that let an operator (or the dashboard toggle) make the
 * simulated bank flaky, so the resilience behaviour can be watched live.
 *
 * Returning the simulator to a healthy state (mode NORMAL with chaos off) is
 * treated as the operator declaring the incident resolved, so the circuit
 * breaker is reset to CLOSED — otherwise every request would keep failing fast
 * until the breaker's own half-open probe eventually clears.
 */
@Component
public class SimulationState {

    public record ChaosProfile(boolean enabled, double failureRate) {}

    private final AtomicReference<SimulationMode> mode;
    private final AtomicReference<ChaosProfile> chaos = new AtomicReference<>(new ChaosProfile(false, 0.0));
    private final CircuitBreaker circuitBreaker;

    public SimulationState(CircuitBreaker bankCircuitBreaker,
                           @Value("${payguard.bank.default-mode:NORMAL}") String defaultMode) {
        this.mode = new AtomicReference<>(SimulationMode.valueOf(defaultMode));
        this.circuitBreaker = bankCircuitBreaker;
    }

    public SimulationMode getMode() { return mode.get(); }

    public void setMode(SimulationMode mode) {
        this.mode.set(mode);
        if (isHealthy()) circuitBreaker.reset();
    }

    public ChaosProfile getChaos() { return chaos.get(); }

    public void setChaos(ChaosProfile profile) {
        this.chaos.set(profile);
        if (isHealthy()) circuitBreaker.reset();
    }

    private boolean isHealthy() {
        ChaosProfile profile = chaos.get();
        boolean chaosQuiet = profile == null || !profile.enabled() || profile.failureRate() <= 0;
        return mode.get() == SimulationMode.NORMAL && chaosQuiet;
    }

    /** Effective behaviour for the next charge: chaos profile can override the static mode randomly. */
    public SimulationMode nextBehaviour() {
        ChaosProfile profile = chaos.get();
        if (profile != null && profile.enabled() && profile.failureRate() > 0) {
            if (Math.random() < profile.failureRate()) {
                SimulationMode[] failures = {
                    SimulationMode.HARD_FAIL,
                    SimulationMode.TIMEOUT,
                    SimulationMode.DROPPED_RESPONSE
                };
                return failures[(int) (Math.random() * failures.length)];
            }
        }
        return mode.get();
    }
}
