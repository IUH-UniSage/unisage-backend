package com.unisage.backend.controller.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.config.ModelRegistryIntegrationSeeder;
import com.unisage.backend.service.modelregistry.ModelRegistryTestResetService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * todo.md Task 0.5: "context profile mặc định ... -> không có bean seeder/controller reset,
 * POST /internal/test/registry/reset (có secret, IP hợp lệ) -> 404". No {@code @ActiveProfiles}
 * here, so the default profile applies — every {@code @Profile("integration")} bean must be
 * absent, and the route itself must not exist (a 404, not a 403 — the request passes the secret
 * and CIDR filters just fine, there is simply no handler mapped).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelRegistryTestResetDefaultProfileIsolationTest {

    private static final String CORRECT_SECRET = "unisage-internal-secret-key-2026";
    private static final String RESET_URL_PATH = "/api/v1/internal/test/registry/reset";

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
    private ApplicationContext applicationContext;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void defaultProfile_seederAndResetBeansAreAbsent() {
        assertThat(applicationContext.getBeanNamesForType(ModelRegistryIntegrationSeeder.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(ModelRegistryTestResetService.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(ModelRegistryTestResetController.class)).isEmpty();
    }

    @Test
    void defaultProfile_resetEndpoint_validSecretAndIp_returns404NotForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + RESET_URL_PATH,
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
