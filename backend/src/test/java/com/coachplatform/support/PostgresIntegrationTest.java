package com.coachplatform.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests from Phase 2 on: real PostgreSQL, real Flyway migrations,
 * Hibernate in validate mode. Use H2 only for trivial tests.
 */
@SpringBootTest
@Testcontainers
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.qr.secret", () -> "integration-qr-secret-integration-qr-32b");
        registry.add("app.jwt.secret", () -> "integration-secret-integration-secret-32b");
        registry.add("app.jwt.expiration-minutes", () -> 60);
        registry.add("app.billing.expiry-job.run-on-startup", () -> false);
    }
}
