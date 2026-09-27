package com.unisage.backend.controller.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * todo.md Task 0.5's own acceptance test: a focused Spring integration test for
 * {@code POST /internal/test/registry/reset} under profile {@code integration} — does not stand
 * up the full cross-repo docker-compose harness (that's {@code unisage-agent/tests/e2e}'s job),
 * only proves the Java side of the contract that harness depends on.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class ModelRegistryTestResetIntegrationProfileTest {

    private static final String CORRECT_SECRET = "unisage-internal-secret-key-2026";
    private static final String RESET_URL_PATH = "/api/v1/internal/test/registry/reset";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void internalProps(DynamicPropertyRegistry registry) {
        registry.add("app.internal.allowed-cidrs", () -> "127.0.0.1/32,::1/128");
        // Seeded credentials point at http://localhost:8000/v1 (the seeder's default) — localhost
        // resolves to loopback in this test JVM, which SsrfGuard blocks unless explicitly
        // allowlisted, same as ChatModelServiceImplTest's existing pattern.
        registry.add("app.model-registry.url-allowlist", () -> "localhost");
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ChatModelRepository chatModelRepository;
    @Autowired
    private ChatModelVerificationRepository chatModelVerificationRepository;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @BeforeEach
    void cleanSlate() {
        chatModelVerificationRepository.deleteAllInBatch();
        chatModelRepository.deleteAllInBatch();
    }

    private ResponseEntity<String> callReset() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", CORRECT_SECRET);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "http://localhost:" + port + RESET_URL_PATH,
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class);
    }

    @Test
    void reset_seedsExactlyTheExpectedFourCredentials() {
        ResponseEntity<String> response = callReset();
        assertThat(response.getStatusCode().value()).isEqualTo(204);

        List<ChatModel> models = chatModelRepository.findAll();
        assertThat(models).hasSize(4);

        List<ChatModel> chatModels = models.stream()
                .filter(m -> m.getModelPurpose() == ChatModelPurpose.CHAT)
                .sorted((a, b) -> a.getPriority().compareTo(b.getPriority()))
                .toList();
        assertThat(chatModels).hasSize(2);
        assertThat(chatModels.get(0).getPriority()).isEqualTo(1);
        assertThat(chatModels.get(1).getPriority()).isEqualTo(2);
        for (ChatModel chat : chatModels) {
            assertThat(chat.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
            assertThat(chat.getVerifiedAt()).isNotNull();
            assertThat(chat.getRevision()).isEqualTo(1);
            assertThat(chat.getIsActive()).isTrue();
        }

        List<ChatModel> embeddingModels = models.stream()
                .filter(m -> m.getModelPurpose() == ChatModelPurpose.EMBEDDING)
                .toList();
        assertThat(embeddingModels).hasSize(1);
        assertThat(embeddingModels.get(0).getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        assertThat(embeddingModels.get(0).getVerifiedAt()).isNotNull();

        List<ChatModel> extractionModels = models.stream()
                .filter(m -> m.getModelPurpose() == ChatModelPurpose.EXTRACTION)
                .toList();
        assertThat(extractionModels).hasSize(1);
        assertThat(extractionModels.get(0).getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        assertThat(extractionModels.get(0).getVerifiedAt()).isNotNull();
    }

    @Test
    void reset_calledTwiceInARow_doesNotErrorOrDuplicateRows() {
        assertThat(callReset().getStatusCode().value()).isEqualTo(204);
        assertThat(chatModelRepository.findAll()).hasSize(4);

        ResponseEntity<String> second = callReset();
        assertThat(second.getStatusCode().value()).isEqualTo(204);
        assertThat(chatModelRepository.findAll()).hasSize(4);
        assertThat(chatModelVerificationRepository.findAll()).hasSize(4);
    }

    @Test
    void reset_blockedByRunningJobWithUnexpiredLease_returns409AndLeavesDataUntouched() {
        assertThat(callReset().getStatusCode().value()).isEqualTo(204);
        List<ChatModel> seededBeforeBlockedAttempt = chatModelRepository.findAll();
        assertThat(seededBeforeBlockedAttempt).hasSize(4);

        ChatModel anyModel = seededBeforeBlockedAttempt.get(0);
        chatModelVerificationRepository.save(ChatModelVerification.builder()
                .chatModel(anyModel)
                .status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(1)
                .baseRevision(1)
                .candidateApiKeyEncrypted("in-flight-candidate")
                .attempt(1)
                .maxAttempts(3)
                .leaseToken(UUID.randomUUID())
                .leaseUntil(LocalDateTime.now().plusSeconds(60))
                .build());

        ResponseEntity<String> blocked = callReset();
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);

        // Nothing was deleted by the rejected attempt.
        assertThat(chatModelRepository.findAll()).hasSize(4);
    }

    /** Sanity check that the seeded row shape really is reachable via {@code ChatModelSourceType}. */
    @Test
    void reset_seededCredentials_areCloudApiWithLlmProvider() {
        callReset();
        for (ChatModel model : chatModelRepository.findAll()) {
            assertThat(model.getSourceType()).isEqualTo(ChatModelSourceType.CLOUD_API);
            assertThat(model.getLlmProvider()).isNotBlank();
        }
    }
}
