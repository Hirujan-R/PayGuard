package com.payguard.fraud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionServiceTest {

    private final RegionService service = new RegionService();

    @Test
    void mapsDocumentationTestRangesToRegions() {
        assertEquals("UK", service.lookup("203.0.113.7"));
        assertEquals("US", service.lookup("198.51.100.9"));
        assertEquals("ASIA", service.lookup("192.0.2.3"));
        assertEquals("UNKNOWN", service.lookup(null));
        assertEquals("UNKNOWN", service.lookup("  "));
    }
}
