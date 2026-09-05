package com.payguard.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically runs the reconciliation job. Disabled via
 * payguard.scheduling.enabled=false (used by integration tests so the job does
 * not race assertions) — the admin endpoint can always trigger a manual run.
 */
@Component
@ConditionalOnProperty(name = "payguard.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationRunner runner;

    public ReconciliationScheduler(ReconciliationRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${payguard.reconcile.interval-ms:5000}")
    public void reconcile() {
        ReconciliationRunner.ReconciliationSummary summary = runner.run();
        if (summary.unknown() > 0 || summary.orphaned() > 0) {
            log.info("reconciliation pass: matched={}, notFound={}, orphaned={}",
                    summary.matched(), summary.notFound(), summary.orphaned());
        }
    }
}
