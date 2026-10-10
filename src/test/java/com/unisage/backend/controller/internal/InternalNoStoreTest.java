package com.unisage.backend.controller.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;

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

    @Autowired
    private ChatModelRepository chatModelRepository;
    @Autowired
    private ChatModelVerificationRepository chatModelVerificationRepository;

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
        String basePath = "/api/v1" + endpoint.get("basePath").asText();
        String path = endpoint.get("path").asText()
                .replace("{collection}", "unisage_chunks")
                .replace("{id}", "00000000-0000-0000-0000-000000000000")
                .replace("{jobId}", "00000000-0000-0000-0000-000000000000");
        String url = "http://localhost:" + port + basePath + path;
        HttpMethod method = HttpMethod.valueOf(endpoint.get("method").asText());

        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Internal-Secret", "unisage-internal-secret-key-2026");
        // Body only for methods that can carry one — enough for POST/PUT/PATCH endpoints to reach
        // validation/handling and produce a response (even a 4xx one), which still must carry
        // no-store; this test only checks headers, never the status code.
        Object body = (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) ? "{}" : null;
        if (body != null) {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        }
        ResponseEntity<String> response = restTemplate.exchange(
                url, method, new org.springframework.http.HttpEntity<>(body, headers), String.class);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
    }

    /**
     * R6.1/R8.5: claim is the endpoint most likely to regress on this, since it's the one where a
     * naive implementation might forget the header on the "actually has a body" path after only
     * testing against an empty queue — the parameterized test above hits claim with nothing to
     * claim (empty `[]` array), so this seeds a real QUEUED job first.
     */
    @Test
    void claimResponse_withActualJob_stillHasNoStore() {
        ChatModel model = ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.ACTIVE)
                .revision(1)
                .candidateGeneration(1)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiBaseUrl("https://api.openai.com/v1")
                .build();
        model.setIsActive(true);
        model = chatModelRepository.save(model);
        chatModelVerificationRepository.save(ChatModelVerification.builder()
                .chatModel(model)
                .status(ChatModelVerificationStatus.QUEUED)
                .candidateGeneration(1)
                .baseRevision(1)
                .candidateApiKeyEncrypted("sk-candidate")
                .attempt(0)
                .maxAttempts(3)
                .build());

        String url = "http://localhost:" + port + "/api/v1/internal/model-registry/verifications/claim?limit=5";
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Internal-Secret", "unisage-internal-secret-key-2026");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, new org.springframework.http.HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotEqualTo("[]"); // sanity: this run actually returned a job
        assertThat(response.getBody()).contains("sk-candidate");
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
