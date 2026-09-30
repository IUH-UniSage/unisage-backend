package com.unisage.backend.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for tests that need a real Postgres running the app's actual Flyway migrations -
 * partial unique indexes, {@code CHECK} constraints, {@code ON DELETE CASCADE}/{@code SET NULL},
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} - none of which H2 enforces the same way (Cost Tracking
 * plan.md Task 1: "chỉ kiểm được trên PostgreSQL"). One container per JVM (static, no
 * {@code @DirtiesContext}) - subclasses that need row-level isolation should clean up their own
 * rows in {@code @AfterEach}, not rely on a fresh schema per test.
 *
 * <p>Mirrors {@code V18MigrationTest}'s container setup exactly (same image, same
 * {@code DB_HOST}/{@code DB_PORT}/{@code DB_NAME}/{@code DB_USER}/{@code DB_PASSWORD} property
 * names the app's own datasource config reads) so every Postgres-backed test in this project boots
 * against the same engine version.
 */
@Testcontainers
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }
}
