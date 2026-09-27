package com.unisage.backend.controller.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP-stack check for {@code /internal/**} where the caller's real remoteAddr (loopback,
 * from the JVM test itself) is deliberately OUTSIDE the configured allowlist — a disjoint CIDR
 * requires a separate Spring context from {@link InternalAccessMatrixAllowedIpTest}, since
 * {@code app.internal.allowed-cidrs} is fixed for the lifetime of one context. Confirms the
 * filter rejects even a correct secret when the real IP doesn't match, and that a header
 * claiming an in-range IP never overrides the real remoteAddr.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalAccessMatrixDeniedIpTest {

    private static final String CORRECT_SECRET = "unisage-internal-secret-key-2026";
    private static final String URL_PATH = "/api/v1/internal/model-registry/version";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void internalProps(DynamicPropertyRegistry registry) {
        // Disjoint from loopback: the real remoteAddr the test JVM connects from is never in range.
        registry.add("app.internal.allowed-cidrs", () -> "10.0.0.0/8");
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private ResponseEntity<String> call(HttpHeaders headers) {
        return restTemplate.exchange("http://localhost:" + port + URL_PATH, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    void correctSecret_ipOutsideCidr_forbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_ipOutsideCidr_xForwardedForPointingInside_stillForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.add("X-Forwarded-For", "10.0.0.5");

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_ipOutsideCidr_forwardedHeaderPointingInside_stillForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.add("Forwarded", "for=10.0.0.5");

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_ipOutsideCidr_xRealIpPointingInside_stillForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.add("X-Real-IP", "10.0.0.5");

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }
}
