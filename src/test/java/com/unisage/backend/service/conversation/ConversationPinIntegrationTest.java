package com.unisage.backend.service.conversation;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.JwtUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Ghim" against real Postgres and the real HTTP stack: V45 column, the bulk UPDATE that leaves
 * updated_at alone, pinned-first ordering, and RBAC (USER role reaches PATCH through CONVERSATION_ALL).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationPinIntegrationTest {

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

    private UUID userId;
    private String userJwt;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        // The seeded end user (role USER) - the role that must reach PATCH /conversations/{id}/pin.
        userId = jdbc.queryForObject("SELECT u.id FROM users u JOIN roles r ON r.id = u.role_id "
                + "WHERE r.name = 'USER' AND u.code = 'US-001'", UUID.class);
        userJwt = jwt(userId, "US-001");
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE code = 'SA-001'", UUID.class);
    }

    @Test
    void pinUnpin_ordersPinnedFirst_andNeverTouchesUpdatedAt() throws Exception {
        Conversation oldest = conversationOf(userId, "Học phí", LocalDateTime.of(2026, 11, 1, 8, 0));
        Conversation middle = conversationOf(userId, "Học phí", LocalDateTime.of(2026, 11, 2, 8, 0));
        Conversation newest = conversationOf(userId, "Lịch thi", LocalDateTime.of(2026, 11, 3, 8, 0));
        List<UUID> mine = List.of(oldest.getId(), middle.getId(), newest.getId());

        assertThat(order(mine)).containsExactly(newest.getId(), middle.getId(), oldest.getId());

        // ── pin the oldest ──
        JsonNode pinned = data(pin(oldest.getId(), "{\"pinned\":true}", userJwt));
        assertThat(pinned.get("id").asText()).isEqualTo(oldest.getId().toString());
        assertThat(pinned.get("pinnedAt").isNull()).isFalse();
        assertThat(pinned.get("updatedAt").asText()).startsWith("2026-11-01T08:00");
        assertThat(updatedAt(oldest.getId())).isEqualTo(LocalDateTime.of(2026, 11, 1, 8, 0));
        assertThat(order(mine)).containsExactly(oldest.getId(), newest.getId(), middle.getId());
        String firstPin = pinned.get("pinnedAt").asText();

        // ── a second pin is newer, so it comes first; re-pinning keeps the original pin time ──
        Thread.sleep(5);
        data(pin(middle.getId(), "{\"pinned\":true}", userJwt));
        assertThat(order(mine)).containsExactly(middle.getId(), oldest.getId(), newest.getId());
        Thread.sleep(5);
        assertThat(data(pin(oldest.getId(), "{\"pinned\":true}", userJwt)).get("pinnedAt").asText())
                .isEqualTo(firstPin);
        assertThat(order(mine)).containsExactly(middle.getId(), oldest.getId(), newest.getId());

        // ── unpin: back to its place by updatedAt, updated_at still untouched ──
        JsonNode unpinned = data(pin(oldest.getId(), "{\"pinned\":false}", userJwt));
        assertThat(unpinned.get("pinnedAt").isNull()).isTrue();
        assertThat(updatedAt(oldest.getId())).isEqualTo(LocalDateTime.of(2026, 11, 1, 8, 0));
        assertThat(order(mine)).containsExactly(middle.getId(), newest.getId(), oldest.getId());

        // ── the list exposes both new fields ──
        JsonNode listed = findById(data(call(HttpMethod.GET, "/conversations", userJwt, null)), middle.getId());
        assertThat(listed.has("updatedAt")).isTrue();
        assertThat(listed.get("pinnedAt").isNull()).isFalse();
    }

    @Test
    void pin_rejectsMissingFlag_othersConversation_deletedAndUnknown() throws Exception {
        Conversation mine = conversationOf(userId, "của tôi", LocalDateTime.of(2026, 11, 5, 8, 0));
        Conversation others = conversationOf(adminId, "của admin", LocalDateTime.of(2026, 11, 5, 8, 0));

        assertError(pin(mine.getId(), "{}", userJwt), 400, ErrorCode.VALIDATION_ERROR, false);
        assertError(pin(others.getId(), "{\"pinned\":true}", userJwt), 404, ErrorCode.CONVERSATION_NOT_FOUND, true);
        assertError(pin(UUID.randomUUID(), "{\"pinned\":true}", userJwt), 404, ErrorCode.CONVERSATION_NOT_FOUND, true);

        assertThat(call(HttpMethod.DELETE, "/conversations/" + mine.getId(), userJwt, null).getStatusCode().value())
                .isEqualTo(200);
        assertError(pin(mine.getId(), "{\"pinned\":true}", userJwt), 404, ErrorCode.CONVERSATION_NOT_FOUND, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations WHERE pinned_at IS NOT NULL AND id IN (?, ?)",
                Long.class, mine.getId(), others.getId())).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Conversation conversationOf(UUID ownerId, String title, LocalDateTime updatedAt) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .user(userRepository.getReferenceById(ownerId)).title(title).build());
        jdbc.update("UPDATE conversations SET updated_at = ? WHERE id = ?", Timestamp.valueOf(updatedAt),
                conversation.getId());
        return conversation;
    }

    private LocalDateTime updatedAt(UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM conversations WHERE id = ?", Timestamp.class, id)
                .toLocalDateTime();
    }

    /** The caller's list, restricted to {@code ids}, in the order the API returns it. */
    private List<UUID> order(List<UUID> ids) throws Exception {
        List<UUID> ordered = new ArrayList<>();
        for (JsonNode node : data(call(HttpMethod.GET, "/conversations", userJwt, null))) {
            UUID id = UUID.fromString(node.get("id").asText());
            if (ids.contains(id)) {
                ordered.add(id);
            }
        }
        return ordered;
    }

    private ResponseEntity<String> pin(UUID id, String body, String jwt) {
        return call(HttpMethod.PATCH, "/conversations/" + id + "/pin", jwt, body);
    }

    private String jwt(UUID id, String code) {
        // Only the id is trusted downstream; DynamicAuthorizationManager reloads the real user.
        User principal = User.builder().id(id).code(code).role(Role.builder().name("ANY").build()).build();
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

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        JsonNode root = json.readTree(response.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(1000);
        return root.get("data");
    }

    private void assertError(ResponseEntity<String> response, int status, ErrorCode code, boolean exactMessage)
            throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        JsonNode root = json.readTree(response.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(code.getCode());
        if (exactMessage) {
            assertThat(root.get("message").asText()).isEqualTo(code.getMessage());
        }
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
