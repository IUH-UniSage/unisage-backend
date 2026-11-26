package com.unisage.backend.service.ticket;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.security.JwtUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Support tickets without a chat message (the support page's "Tạo yêu cầu") against real Postgres
 * (V46: nullable message_id, new types, tickets_message_type_check) and the real HTTP stack, for the
 * owner and for staff.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeneralTicketIntegrationTest {

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

    private UUID userId;
    private String userJwt;
    private String staffJwt;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject("SELECT id FROM users WHERE code = 'US-001'", UUID.class);
        userJwt = jwt(userId, "US-001");
        staffJwt = jwt(jdbc.queryForObject("SELECT id FROM users WHERE code = 'SA-001'", UUID.class), "SA-001");
    }

    @Test
    void generalRequest_isFiledWithoutAMessage_andReadableByOwnerAndStaff() throws Exception {
        ResponseEntity<String> created = call(HttpMethod.POST, "/tickets", userJwt,
                "{\"type\":\"ACCOUNT_ISSUE\",\"title\":\"Không đăng nhập được\",\"description\":\"Quên mật khẩu\"}");
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        JsonNode ticket = data(created);
        UUID id = UUID.fromString(ticket.get("id").asText());
        assertThat(ticket.get("messageId").isNull()).isTrue();
        assertThat(ticket.get("type").asText()).isEqualTo("ACCOUNT_ISSUE");
        assertThat(ticket.get("status").asText()).isEqualTo("OPEN");

        // NULL message_id never collides in the partial unique index: a second one is fine.
        assertThat(call(HttpMethod.POST, "/tickets", userJwt,
                "{\"type\":\"GENERAL_QUESTION\",\"title\":\"Hỏi\",\"description\":\"Học bổng?\"}")
                .getStatusCode().value()).isEqualTo(201);
        assertThat(call(HttpMethod.POST, "/tickets", userJwt,
                "{\"messageId\":null,\"type\":\"OTHER\",\"title\":\"Khác\",\"description\":\"Góp ý\"}")
                .getStatusCode().value()).isEqualTo(201);

        for (String path : List.of("/tickets/my/" + id, "/tickets/" + id)) {
            String jwt = path.startsWith("/tickets/my") ? userJwt : staffJwt;
            JsonNode detail = data(call(HttpMethod.GET, path, jwt, null));
            assertThat(detail.get("messageId").isNull()).as(path).isTrue();
            assertThat(detail.get("conversationId").isNull()).as(path).isTrue();
            assertThat(detail.get("questionContent").isNull()).as(path).isTrue();
            assertThat(detail.get("messageContent").isNull()).as(path).isTrue();
            assertThat(detail.get("citations").isNull()).as(path).isTrue();
            assertThat(detail.get("traceId").isNull()).as(path).isTrue();
            assertThat(detail.get("description").asText()).isEqualTo("Quên mật khẩu");
        }
        assertThat(data(call(HttpMethod.GET, "/tickets/my", userJwt, null)).get("data")).hasSizeGreaterThanOrEqualTo(3);
        JsonNode staffList = data(call(HttpMethod.GET, "/tickets?type=ACCOUNT_ISSUE&q=mật khẩu", staffJwt, null));
        assertThat(staffList.get("data")).anySatisfy(t -> assertThat(t.get("id").asText()).isEqualTo(id.toString()));

        JsonNode resolved = data(call(HttpMethod.PATCH, "/tickets/" + id, staffJwt,
                "{\"status\":\"RESOLVED\",\"resolution\":\"Đã cấp lại mật khẩu\"}"));
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
    }

    @Test
    void typeAndMessageMustMatch() throws Exception {
        assertError(call(HttpMethod.POST, "/tickets", userJwt,
                "{\"type\":\"AI_UNANSWERED\",\"title\":\"t\",\"description\":\"d\"}"),
                ErrorCode.TICKET_TYPE_MESSAGE_MISMATCH);
        assertError(call(HttpMethod.POST, "/tickets", userJwt,
                "{\"messageId\":\"" + UUID.randomUUID() + "\",\"type\":\"GENERAL_QUESTION\",\"title\":\"t\","
                        + "\"description\":\"d\"}"),
                ErrorCode.TICKET_TYPE_MESSAGE_MISMATCH);
        assertError(call(HttpMethod.POST, "/tickets", userJwt,
                "{\"type\":\"AI_CALCULATION_WRONG\",\"title\":\"t\",\"description\":\"d\"}"),
                ErrorCode.TICKET_TYPE_NOT_REPORTABLE);

        // The same rule in the database (V46 tickets_message_type_check).
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tickets (id, description, status, title, type, user_id) "
                + "VALUES (gen_random_uuid(), 'd', 'OPEN', 't', 'AI_UNANSWERED', ?)", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

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

    private void assertError(ResponseEntity<String> response, ErrorCode code) throws Exception {
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(code.getHttpStatus().value());
        JsonNode root = json.readTree(response.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(code.getCode());
        assertThat(root.get("message").asText()).isEqualTo(code.getMessage());
    }
}
