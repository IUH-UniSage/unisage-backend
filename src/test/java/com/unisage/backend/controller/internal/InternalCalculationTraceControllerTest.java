package com.unisage.backend.controller.internal;

import java.time.LocalDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.CalculationTrace;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.repository.CalculationTraceRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** UNISAGE-99 T21a: {@code POST /internal/calculation-traces} - secret, validation, idempotent upsert. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalCalculationTraceControllerTest {

    private static final String SECRET = "unisage-internal-secret-key-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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
    private GuestSessionRepository guestSessionRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private CalculationTraceRepository calculationTraceRepository;

    private Message assistant;

    @BeforeEach
    void setUp() {
        GuestSession guestSession = guestSessionRepository.save(GuestSession.builder()
                .tokenHash(UUID.randomUUID().toString())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .guestSession(guestSession)
                .title("traces")
                .build());
        assistant = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("answer")
                .status(MsgStatus.COMPLETED)
                .build());
    }

    private ResponseEntity<String> post(String body, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            headers.add("X-Internal-Secret", secret);
        }
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/internal/calculation-traces",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String payload(UUID messageId, String itemsJson) {
        return """
                {"messageId": "%s", "items": %s}
                """.formatted(messageId, itemsJson);
    }

    private String item(String itemId, String runId, String answer) {
        return """
                {"itemId": "%s", "runId": "%s", "trace": {"mode": "llm", "answer": "%s"}}
                """.formatted(itemId, runId, answer);
    }

    @Test
    void missingOrWrongSecret_isForbidden() {
        String body = payload(assistant.getId(), "[" + item("T1", "run-1", "a*b") + "]");

        assertThat(post(body, null).getStatusCode().value()).isEqualTo(403);
        assertThat(post(body, "not-the-secret").getStatusCode().value()).isEqualTo(403);
        assertThat(calculationTraceRepository.findByMessageIdAndItemId(assistant.getId(), "T1")).isEmpty();
    }

    @Test
    void sameItemPushedTwice_isUpsertedNotDuplicated() {
        String first = payload(assistant.getId(),
                "[" + item("T1", "run-1", "a*b") + "," + item("T2", "run-1", "c+d") + "]");
        String retry = payload(assistant.getId(), "[" + item("T1", "run-2", "a*b*2") + "]");

        ResponseEntity<String> firstResponse = post(first, SECRET);
        ResponseEntity<String> retryResponse = post(retry, SECRET);

        assertThat(firstResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(firstResponse.getBody()).contains("\"itemIds\":[\"T1\",\"T2\"]");
        assertThat(retryResponse.getStatusCode().value()).isEqualTo(200);
        CalculationTrace t1 = calculationTraceRepository.findByMessageIdAndItemId(assistant.getId(), "T1")
                .orElseThrow();
        assertThat(t1.getRunId()).isEqualTo("run-2");
        assertThat(t1.getTrace()).containsEntry("answer", "a*b*2");
        assertThat(calculationTraceRepository.findByMessageIdAndItemId(assistant.getId(), "T2")).isPresent();
        assertThat(calculationTraceRepository.findAll().stream()
                .filter(t -> t.getMessageId().equals(assistant.getId()))).hasSize(2);
    }

    @Test
    void invalidPayloads_areBadRequest() {
        UUID id = assistant.getId();
        String fourItems = "[" + item("T1", "r", "x") + "," + item("T2", "r", "x") + ","
                + item("T3", "r", "x") + "," + item("T1", "r", "x") + "]";

        assertThat(post(payload(id, "[" + item("T4", "r", "x") + "]"), SECRET).getStatusCode().value())
                .isEqualTo(400);
        assertThat(post(payload(id, "[]"), SECRET).getStatusCode().value()).isEqualTo(400);
        assertThat(post(payload(id, fourItems), SECRET).getStatusCode().value()).isEqualTo(400);
        assertThat(post(payload(id, "[" + item("T1", "r", "x") + "," + item("T1", "r", "y") + "]"), SECRET)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post(payload(id, "[" + item("T1", "r", "x".repeat(16 * 1024)) + "]"), SECRET)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownOrNonAssistantMessage_isNotFound() {
        Message user = messageRepository.save(Message.builder()
                .conversation(assistant.getConversation())
                .role(MsgRole.USER)
                .content("question")
                .status(MsgStatus.COMPLETED)
                .build());

        assertThat(post(payload(UUID.randomUUID(), "[" + item("T1", "r", "x") + "]"), SECRET)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(post(payload(user.getId(), "[" + item("T1", "r", "x") + "]"), SECRET)
                .getStatusCode().value()).isEqualTo(404);
    }
}
