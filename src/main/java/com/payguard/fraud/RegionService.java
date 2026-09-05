package com.payguard.fraud;

import org.springframework.stereotype.Component;

/**
 * Mocked IP-to-region lookup (a real deployment would call a geo-IP service,
 * this maps documentation test ranges to a coarse region). Kept intentionally
 * simple and deterministic so the geo-jump rule is easy to demonstrate.
 */
@Component
public class RegionService {

    public String lookup(String ip) {
        if (ip == null || ip.isBlank()) {
            return "UNKNOWN";
        }
        if (ip.startsWith("192.0.2.")) return "ASIA";
        if (ip.startsWith("198.51.100.")) return "US";
        if (ip.startsWith("203.0.113.")) return "UK";
        if (ip.startsWith("10.")) return "EU";
        return "LOCAL";
    }
}
