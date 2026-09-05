package com.payguard.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves Prometheus-format metrics directly at GET /metrics (the spec's
 * endpoint). In production the scrape target would use /actuator/prometheus;
 * this is a convenience alias for the local dashboard.
 */
@RestController
public class MetricsController {

    private final ObjectProvider<PrometheusMeterRegistry> prometheus;

    public MetricsController(ObjectProvider<PrometheusMeterRegistry> prometheus) {
        this.prometheus = prometheus;
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics() {
        PrometheusMeterRegistry registry = prometheus.getIfAvailable();
        return registry == null ? "# prometheus registry not enabled\n" : registry.scrape();
    }
}
