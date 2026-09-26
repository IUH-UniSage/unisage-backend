package com.unisage.backend.controller.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every /internal/** response must carry no-store — parameterized by contracts/internal-endpoints.json
 * so a new endpoint is covered automatically once marked "implemented": true.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalNoStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

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

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    static List<JsonNode> implementedEndpoints() throws IOException {
        try (InputStream in = new java.io.FileInputStream("contracts/internal-endpoints.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode endpoint : root.get("endpoints")) {
                if (endpoint.get("implemented").asBoolean()) {
                    result.add(endpoint);
                }
            }
            return result;
        }
    }

    @ParameterizedTest
    @MethodSource("implementedEndpoints")
    void implementedEndpoint_hasNoStore(JsonNode endpoint) {
        String basePath = "/api/v1/internal/model-registry";
        String path = endpoint.get("path").asText().replace("{collection}", "unisage_chunks");
        String url = "http://localhost:" + port + basePath + path;
        HttpMethod method = HttpMethod.valueOf(endpoint.get("method").asText());

        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Internal-Secret", "unisage-internal-secret-key-2026");
        ResponseEntity<String> response = restTemplate.exchange(
                url, method, new org.springframework.http.HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
    }

    @Test
    void forbiddenResponse_alsoHasNoStore() {
        String url = "http://localhost:" + port + "/api/v1/internal/model-registry/version";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }
}
