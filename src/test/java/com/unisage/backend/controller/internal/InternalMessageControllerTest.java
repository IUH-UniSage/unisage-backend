package com.unisage.backend.controller.internal;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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

import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** UNISAGE-99 T4: {@code PATCH /internal/messages/{id}/clarification} end to end (secret + real jsonb). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalMessageControllerTest {

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

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        GuestSession guestSession = guestSessionRepository.save(GuestSession.builder()
                .tokenHash(UUID.randomUUID().toString())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        conversation = conversationRepository.save(Conversation.builder()
                .guestSession(guestSession)
                .title("clarification")
                .build());
    }

    private String url(UUID messageId) {
        return "http://localhost:" + port + "/api/v1/internal/messages/" + messageId + "/clarification";
    }

    private ResponseEntity<String> patch(UUID messageId, UUID conversationId, String status, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (secret != null) {
            headers.add("X-Internal-Secret", secret);
        }
        String body = """
                {"conversationId": "%s", "status": "%s"}
                """.formatted(conversationId, status);
        return restTemplate.exchange(url(messageId), HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private Message savePanelMessage(MsgRole role, String status) {
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("schema_version", 1);
        clarification.put("status", status);
        clarification.put("panel", Map.of("panel_id", "p-1", "questions", java.util.List.of()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clarification", clarification);
        metadata.put("calculation", Map.of("schema_version", 1));
        return messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(role)
                .content("Bạn thuộc khoá nào?")
                .status(MsgStatus.COMPLETED)
                .metadata(metadata)
                .build());
    }

    @Test
    void missingSecret_isForbidden() {
        Message message = savePanelMessage(MsgRole.ASSISTANT, "open");

        assertThat(patch(message.getId(), conversation.getId(), "cancelled", null).getStatusCode().value())
                .isEqualTo(403);
        assertThat(patch(message.getId(), conversation.getId(), "cancelled", "wrong").getStatusCode().value())
                .isEqualTo(403);
        assertThat(statusOf(message.getId())).isEqualTo("open");
    }

    @Test
    void cancel_flipsStatus_keepsOtherKeys_andIsIdempotent() {
        Message message = savePanelMessage(MsgRole.ASSISTANT, "open");

        ResponseEntity<String> first = patch(message.getId(), conversation.getId(), "cancelled", SECRET);
        ResponseEntity<String> second = patch(message.getId(), conversation.getId(), "cancelled", SECRET);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody()).contains("\"status\":\"cancelled\"");
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        Message reloaded = messageRepository.findById(message.getId()).orElseThrow();
        assertThat(statusOf(message.getId())).isEqualTo("cancelled");
        assertThat(reloaded.getMetadata()).containsKey("calculation");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) reloaded.getMetadata().get("clarification");
        assertThat(clarification).containsKeys("panel", "schema_version");
        assertThat(reloaded.getContent()).isEqualTo("Bạn thuộc khoá nào?");
        assertThat(reloaded.getStatus()).isEqualTo(MsgStatus.COMPLETED);
    }

    @Test
    void wrongConversation_userMessage_orUnknownMessage_isNotFound() {
        Message assistant = savePanelMessage(MsgRole.ASSISTANT, "open");
        Message user = savePanelMessage(MsgRole.USER, "open");

        assertThat(patch(assistant.getId(), UUID.randomUUID(), "cancelled", SECRET).getStatusCode().value())
                .isEqualTo(404);
        assertThat(patch(user.getId(), conversation.getId(), "cancelled", SECRET).getStatusCode().value())
                .isEqualTo(404);
        assertThat(patch(UUID.randomUUID(), conversation.getId(), "cancelled", SECRET).getStatusCode().value())
                .isEqualTo(404);
        assertThat(statusOf(assistant.getId())).isEqualTo("open");
    }

    @Test
    void otherTransition_isConflict() {
        Message message = savePanelMessage(MsgRole.ASSISTANT, "cancelled");

        assertThat(patch(message.getId(), conversation.getId(), "open", SECRET).getStatusCode().value())
                .isEqualTo(409);
        assertThat(statusOf(message.getId())).isEqualTo("cancelled");
    }

    private String statusOf(UUID messageId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) messageRepository.findById(messageId)
                .orElseThrow().getMetadata().get("clarification");
        return (String) clarification.get("status");
    }
}
