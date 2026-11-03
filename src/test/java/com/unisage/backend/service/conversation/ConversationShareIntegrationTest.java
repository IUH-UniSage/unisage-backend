package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.JwtUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNISAGE-107 against real Postgres (V42 partial unique index, row-value cutoff, the retention
 * CTE) and the real HTTP stack (/shares/** authenticated-only, JWT identity, error JSON).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationShareIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ConversationService conversationService;

    private UUID ownerId;
    private UUID readerId;
    private String ownerJwt;
    private String readerJwt;

    @BeforeEach
    void setUp() {
        // Two seeded, active users with a role (DataInitializer); any role works for /shares/**.
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, code FROM users WHERE role_id IS NOT NULL AND is_active ORDER BY code LIMIT 2");
        assertThat(users).hasSize(2);
        ownerId = (UUID) users.get(0).get("id");
        readerId = (UUID) users.get(1).get("id");
        ownerJwt = jwt(ownerId, (String) users.get(0).get("code"));
        readerJwt = jwt(readerId, (String) users.get(1).get("code"));
    }

    @Test
    void shareViewContinueRevoke_endToEnd() throws Exception {
        Conversation original = conversationOf(ownerId, "Học phí K68");
        Message question = save(original, MsgRole.USER, "Học phí bao nhiêu?", MsgStatus.COMPLETED, null, null);
        Map<String, Object> answerMetadata = new LinkedHashMap<>();
        answerMetadata.put("calculation", Map.of("schema_version", 1, "items", List.of(Map.of("item_id", "T1"))));
        answerMetadata.put("calculation_feedback", Map.of("T1", Map.of("verdict", "WRONG")));
        save(original, MsgRole.ASSISTANT, "8.400.000đ", MsgStatus.COMPLETED, answerMetadata,
                List.of(Map.of("index", 1, "title", "Quy chế", "documentId", "d1")));
        save(original, MsgRole.USER, "Còn K69?", MsgStatus.COMPLETED, null, null);
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("clarification", Map.of("schema_version", 1, "status", "open", "panel", Map.of("panel_id", "p1")));
        save(original, MsgRole.ASSISTANT, "Bạn thuộc khoá nào?", MsgStatus.COMPLETED, panel, null);

        // ── create ──
        ResponseEntity<String> created = call(HttpMethod.POST, "/shares", ownerJwt,
                "{\"conversationId\":\"" + original.getId() + "\"}");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode share = data(created);
        String token = share.get("token").asText();
        UUID shareId = UUID.fromString(share.get("id").asText());
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(share.get("title").asText()).isEqualTo("Học phí K68");
        assertThat(share.get("messageCount").asInt()).isEqualTo(4);
        assertThat(share.has("createdAt")).isTrue();

        // The owner keeps chatting: the link must not change.
        save(original, MsgRole.USER, "Tin nhắn sau khi chia sẻ", MsgStatus.COMPLETED, null, null);
        save(original, MsgRole.ASSISTANT, "", MsgStatus.STREAMING, null, null);

        // ── view ──
        ResponseEntity<String> readerView = call(HttpMethod.GET, "/shares/view/" + token, readerJwt, null);
        assertThat(readerView.getStatusCode().value()).isEqualTo(200);
        JsonNode view = data(readerView);
        assertThat(view.get("isOwner").asBoolean()).isFalse();
        assertThat(view.has("conversationId")).isFalse();
        assertThat(view.get("title").asText()).isEqualTo("Học phí K68");
        assertThat(view.get("messages")).hasSize(4);
        assertThat(view.get("messages").get(0).get("content").asText()).isEqualTo("Học phí bao nhiêu?");
        assertThat(view.get("messages").get(0).get("id").asText()).isEqualTo(question.getId().toString());
        assertThat(view.get("messages").get(1).get("metadata").has("calculation")).isTrue();
        assertThat(readerView.getBody()).doesNotContain("Tin nhắn sau khi chia sẻ")
                .doesNotContain("calculation_feedback")
                .doesNotContain(original.getId().toString());

        JsonNode ownerView = data(call(HttpMethod.GET, "/shares/view/" + token, ownerJwt, null));
        assertThat(ownerView.get("isOwner").asBoolean()).isTrue();
        assertThat(ownerView.get("conversationId").asText()).isEqualTo(original.getId().toString());

        // Signed out: never readable.
        // (Through the gateway a signed-out browser gets the gateway's own 401 first.)
        assertThat(call(HttpMethod.GET, "/shares/view/" + token, null, null).getStatusCode().value()).isEqualTo(403);
        // The reader can't bypass the link through the original's UUID (§4).
        ResponseEntity<String> direct = call(HttpMethod.GET, "/messages/conversation/" + original.getId(), readerJwt, null);
        assertThat(direct.getStatusCode().value()).isEqualTo(404);
        assertThat(direct.getBody()).contains("\"code\":" + ErrorCode.CONVERSATION_NOT_FOUND.getCode());

        // ── continue ──
        ResponseEntity<String> continued = call(HttpMethod.POST, "/shares/view/" + token + "/continue", readerJwt, null);
        assertThat(continued.getStatusCode().value()).isEqualTo(200);
        UUID copyId = UUID.fromString(data(continued).get("conversationId").asText());
        UUID again = UUID.fromString(data(call(HttpMethod.POST, "/shares/view/" + token + "/continue", readerJwt, null))
                .get("conversationId").asText());
        assertThat(again).isEqualTo(copyId);

        Conversation copy = conversationRepository.findById(copyId).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT user_id FROM conversations WHERE id = ?", UUID.class, copyId))
                .isEqualTo(readerId);
        assertThat(jdbc.queryForObject("SELECT source_share_id FROM conversations WHERE id = ?", UUID.class, copyId))
                .isEqualTo(shareId);
        assertThat(copy.getTitle()).isEqualTo("Học phí K68");
        List<Message> copies = messageRepository.findByConversationIdOrderByCreatedAtAsc(copyId);
        assertThat(copies).extracting(Message::getContent)
                .containsExactly("Học phí bao nhiêu?", "8.400.000đ", "Còn K69?", "Bạn thuộc khoá nào?");
        for (int i = 1; i < copies.size(); i++) {
            assertThat(copies.get(i).getCreatedAt()).isAfter(copies.get(i - 1).getCreatedAt());
        }
        assertThat(copies.get(1).getMetadata()).containsKey("calculation").doesNotContainKey("calculation_feedback");
        assertThat(copies.get(1).getCitations()).hasSize(1);
        assertThat(((Map<?, ?>) copies.get(3).getMetadata().get("clarification")).get("status")).isEqualTo("cancelled");
        assertThat(conversationService.getByUser(readerId)).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(copyId);
            assertThat(c.sourceShareId()).isEqualTo(shareId);
        });
        // The copy is the reader's own conversation now.
        assertThat(call(HttpMethod.GET, "/messages/conversation/" + copyId, readerJwt, null).getStatusCode().value())
                .isEqualTo(200);

        // ── list ──
        JsonNode page = data(call(HttpMethod.GET, "/shares?page=1&limit=50", ownerJwt, null));
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("data").findValuesAsText("id")).contains(shareId.toString());
        JsonNode item = findById(page.get("data"), shareId);
        assertThat(item.get("conversationId").asText()).isEqualTo(original.getId().toString());
        assertThat(item.get("token").asText()).isEqualTo(token);
        assertThat(item.get("messageCount").asInt()).isEqualTo(4);
        assertThat(data(call(HttpMethod.GET, "/shares", readerJwt, null)).get("data").findValuesAsText("id"))
                .doesNotContain(shareId.toString());

        // ── revoke ──
        ResponseEntity<String> foreignRevoke = call(HttpMethod.DELETE, "/shares/" + shareId, readerJwt, null);
        assertThat(foreignRevoke.getStatusCode().value()).isEqualTo(404);
        assertThat(foreignRevoke.getBody()).contains("\"code\":" + ErrorCode.SHARE_NOT_FOUND.getCode());
        assertThat(call(HttpMethod.DELETE, "/shares/" + shareId, ownerJwt, null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.DELETE, "/shares/" + shareId, ownerJwt, null).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> revokedView = call(HttpMethod.GET, "/shares/view/" + token, readerJwt, null);
        assertThat(revokedView.getStatusCode().value()).isEqualTo(404);
        assertThat(revokedView.getBody()).contains("\"code\":" + ErrorCode.SHARE_NOT_FOUND.getCode());
        assertThat(call(HttpMethod.POST, "/shares/view/" + token + "/continue", readerJwt, null)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(data(call(HttpMethod.GET, "/shares?page=1&limit=50", ownerJwt, null)).get("data")
                .findValuesAsText("id")).doesNotContain(shareId.toString());
        // The reader's copy outlives the link.
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(copyId)).hasSize(4);
    }

    @Test
    void createErrors_followTheSpec() throws Exception {
        Conversation empty = conversationOf(ownerId, "rỗng");
        save(empty, MsgRole.ASSISTANT, "", MsgStatus.STREAMING, null, null);
        assertError(call(HttpMethod.POST, "/shares", ownerJwt, body(empty.getId())), 400, ErrorCode.SHARE_EMPTY_CONVERSATION);

        Conversation mine = conversationOf(ownerId, "của A");
        save(mine, MsgRole.USER, "q", MsgStatus.COMPLETED, null, null);
        assertError(call(HttpMethod.POST, "/shares", readerJwt, body(mine.getId())), 404, ErrorCode.CONVERSATION_NOT_FOUND);
        assertError(call(HttpMethod.POST, "/shares", ownerJwt, body(UUID.randomUUID())), 404, ErrorCode.CONVERSATION_NOT_FOUND);
        assertError(call(HttpMethod.GET, "/shares/view/khong-ton-tai", readerJwt, null), 404, ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    void deletingTheConversation_revokesItsLinks() throws Exception {
        Conversation conversation = conversationOf(ownerId, "sẽ xoá");
        save(conversation, MsgRole.USER, "q", MsgStatus.COMPLETED, null, null);
        String token = data(call(HttpMethod.POST, "/shares", ownerJwt, body(conversation.getId()))).get("token").asText();

        conversationService.softDelete(conversation.getId(), ownerId);

        assertError(call(HttpMethod.GET, "/shares/view/" + token, readerJwt, null), 404, ErrorCode.SHARE_NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM conversation_shares WHERE token = ?",
                LocalDateTime.class, token)).isNotNull();
    }

    @Test
    void revokeAll_returnsTheCount_andEmptiesTheList() throws Exception {
        Conversation conversation = conversationOf(ownerId, "nhiều link");
        save(conversation, MsgRole.USER, "q", MsgStatus.COMPLETED, null, null);
        call(HttpMethod.POST, "/shares", ownerJwt, body(conversation.getId()));
        call(HttpMethod.POST, "/shares", ownerJwt, body(conversation.getId()));

        ResponseEntity<String> revoked = call(HttpMethod.DELETE, "/shares", ownerJwt, null);

        assertThat(revoked.getStatusCode().value()).isEqualTo(200);
        assertThat(data(revoked).asInt()).isGreaterThanOrEqualTo(2);
        JsonNode page = data(call(HttpMethod.GET, "/shares", ownerJwt, null));
        assertThat(page.get("data")).isEmpty();
        assertThat(page.get("totalItems").asInt()).isZero();
        assertThat(data(call(HttpMethod.DELETE, "/shares", ownerJwt, null)).asInt()).isZero();
    }

    @Test
    void retentionJob_revokesTheLinksOfTheConversationsItDeletes() throws Exception {
        Conversation older = conversationOf(ownerId, "cũ");
        save(older, MsgRole.USER, "q", MsgStatus.COMPLETED, null, null);
        String token = data(call(HttpMethod.POST, "/shares", ownerJwt, body(older.getId()))).get("token").asText();
        Conversation newer = conversationOf(ownerId, "mới");
        save(newer, MsgRole.USER, "q", MsgStatus.COMPLETED, null, null);

        assertThat(conversationService.trimToMaxPerOwner(1)).isPositive();

        assertThat(conversationRepository.findById(older.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM conversation_shares WHERE token = ?",
                LocalDateTime.class, token)).isNotNull();
        assertError(call(HttpMethod.GET, "/shares/view/" + token, readerJwt, null), 404, ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    void concurrentContinue_yieldsOneCopy() throws Exception {
        Conversation conversation = conversationOf(ownerId, "double click");
        for (int i = 0; i < 5; i++) {
            save(conversation, i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT, "m" + i, MsgStatus.COMPLETED, null, null);
        }
        String token = data(call(HttpMethod.POST, "/shares", ownerJwt, body(conversation.getId()))).get("token").asText();
        Callable<String> click = () -> data(call(HttpMethod.POST, "/shares/view/" + token + "/continue", readerJwt, null))
                .get("conversationId").asText();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> results = pool.invokeAll(List.of(click, click, click, click));
            String first = results.get(0).get();
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo(first);
            }
            Integer live = jdbc.queryForObject("SELECT count(*) FROM conversations c JOIN conversation_shares s "
                    + "ON c.source_share_id = s.id WHERE s.token = ? AND c.user_id = ? AND c.deleted_at IS NULL",
                    Integer.class, token, readerId);
            assertThat(live).isEqualTo(1);
            assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(UUID.fromString(first))).hasSize(5);
        } finally {
            pool.shutdownNow();
        }

        // Deleting the copy frees the slot: the next "Tiếp tục" makes a new one.
        UUID copyId = UUID.fromString(click.call());
        conversationService.softDelete(copyId, readerId);
        assertThat(UUID.fromString(click.call())).isNotEqualTo(copyId);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Conversation conversationOf(UUID userId, String title) {
        return conversationRepository.save(Conversation.builder()
                .user(userRepository.getReferenceById(userId)).title(title).build());
    }

    private Message save(Conversation conversation, MsgRole role, String content, MsgStatus status,
            Map<String, Object> metadata, List<Map<String, Object>> citations) throws InterruptedException {
        Message message = messageRepository.save(Message.builder()
                .conversation(conversation).role(role).content(content).status(status)
                .metadata(metadata).citations(citations).build());
        Thread.sleep(2);
        return message;
    }

    private String jwt(UUID userId, String code) {
        // Only the id is trusted downstream; DynamicAuthorizationManager reloads the real user.
        User principal = User.builder().id(userId).code(code).role(Role.builder().name("ANY").build()).build();
        return jwtUtil.generateAccessToken(principal, List.of(), List.of());
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String jwt, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return rest.exchange("http://localhost:" + port + "/api/v1" + path, method,
                new HttpEntity<>(body, headers), String.class);
    }

    private static String body(UUID conversationId) {
        return "{\"conversationId\":\"" + conversationId + "\"}";
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        JsonNode root = json.readTree(response.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(1000);
        return root.get("data");
    }

    private void assertError(ResponseEntity<String> response, int status, ErrorCode code) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        JsonNode root = json.readTree(response.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(code.getCode());
        assertThat(root.get("message").asText()).isEqualTo(code.getMessage());
        assertThat(root.has("data")).isFalse();
    }

    private static JsonNode findById(JsonNode items, UUID id) {
        for (JsonNode node : items) {
            if (node.get("id").asText().equals(id.toString())) {
                return node;
            }
        }
        throw new AssertionError("no item " + id);
    }
}
