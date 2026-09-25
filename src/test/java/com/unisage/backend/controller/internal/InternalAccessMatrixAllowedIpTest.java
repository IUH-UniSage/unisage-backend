package com.unisage.backend.controller.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.security.JwtUtil;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP-stack matrix for {@code /internal/**} with the caller's real remoteAddr (loopback)
 * INSIDE the allowed CIDR — complements the filter-level unit tests with an end-to-end check
 * that {@link com.unisage.backend.security.InternalSecretFilter}, {@link
 * com.unisage.backend.security.InternalCallerCidrFilter} and {@code DynamicAuthorizationManager}
 * agree on the outcome, and that forwarded-for style headers are ignored entirely (an allowed
 * real IP is not defeated by a header claiming a different one).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalAccessMatrixAllowedIpTest {

    private static final String CORRECT_SECRET = "unisage-internal-secret-key-2026";
    private static final String URL_PATH = "/api/v1/internal/model-registry/version";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void internalProps(DynamicPropertyRegistry registry) {
        registry.add("app.internal.allowed-cidrs", () -> "127.0.0.1/32,::1/128");
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String url() {
        return "http://localhost:" + port + URL_PATH;
    }

    private ResponseEntity<String> call(HttpHeaders headers) {
        return restTemplate.exchange(url(), org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private String saJwt() {
        User fakeSaUser = User.builder()
                .id(UUID.randomUUID())
                .code("SA001")
                .role(Role.builder().name("SUPER_ADMIN").build())
                .build();
        return jwtUtil.generateAccessToken(fakeSaUser, java.util.List.of(), java.util.List.of());
    }

    @Test
    void correctSecret_noJwt_ok() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);

        assertThat(call(headers).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void missingSecret_forbidden() {
        assertThat(call(new HttpHeaders()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void validJwtNoSecret_forbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + saJwt());

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void wrongSecret_forbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", "not-the-secret");

        assertThat(call(headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_ipAllowed_xForwardedForPointingOutside_stillOk() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.add("X-Forwarded-For", "8.8.8.8");
        headers.add("Forwarded", "for=8.8.8.8");
        headers.add("X-Real-IP", "8.8.8.8");

        assertThat(call(headers).getStatusCode().value()).isEqualTo(200);
    }
}
