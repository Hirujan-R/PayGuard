package com.payguard.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payguard.reconcile")
public class ReconcileProperties {

    private long intervalMs = 5000;
    private long unknownGraceMs = 8000;
    private long pendingGraceMs = 60000;

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public long getUnknownGraceMs() { return unknownGraceMs; }
    public void setUnknownGraceMs(long unknownGraceMs) { this.unknownGraceMs = unknownGraceMs; }
    public long getPendingGraceMs() { return pendingGraceMs; }
    public void setPendingGraceMs(long pendingGraceMs) { this.pendingGraceMs = pendingGraceMs; }
}
