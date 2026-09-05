package com.payguard.integration;

import com.payguard.PayGuardApplication;
import com.payguard.payment.PaymentDtos.CreatePaymentRequest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real Postgres (Flyway + JPA + native queries).
 *
 * Two supported modes:
 *   1. Testcontainers: boots one ephemeral PostgreSQL container (default).
 *   2. External Postgres: if PAYGUARD_TEST_DB_URL is set the suite runs against
 *      that database instead — e.g. `docker compose up -d db` then
 *      `PAYGUARD_TEST_DB_URL=jdbc:postgresql://localhost:5432/payguard mvn test`.
 *
 * The reconciliation timer and circuit-breaker timings are tuned for fast,
 * deterministic tests.
 */
@SpringBootTest(classes = PayGuardApplication.class, properties = {
        "payguard.scheduling.enabled=false",
        "payguard.reconcile.unknown-grace-ms=0",
        "payguard.reconcile.pending-grace-ms=0",
        "payguard.resilience.circuit-breaker.sliding-window-size=6",
        "payguard.resilience.circuit-breaker.failure-rate-threshold=50",
        "payguard.resilience.circuit-breaker.wait-duration-in-open-state-ms=500"
})
public abstract class AbstractIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("PAYGUARD_TEST_DB_URL");
    private static final boolean USE_EXTERNAL = EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payguard_test")
            .withUsername("payguard")
            .withPassword("payguard");

    static {
        if (!USE_EXTERNAL) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("PAYGUARD_TEST_DB_USER", "payguard"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("PAYGUARD_TEST_DB_PASSWORD", "payguard"));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }

    protected static CreatePaymentRequest request(String accountId, long amountMinor, String ip) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setAccountId(accountId);
        request.setAmountMinor(amountMinor);
        request.setCurrency("GBP");
        request.setDescription("integration test");
        request.setIpAddress(ip);
        return request;
    }
}
