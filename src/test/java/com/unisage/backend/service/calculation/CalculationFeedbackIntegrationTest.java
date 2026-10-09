package com.unisage.backend.service.calculation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.request.CreateTicketRequest;
import com.unisage.backend.dto.request.internal.CalculationTraceIngestRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.dto.response.TicketResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.entity.enums.CalculationWrongReason;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.UserPrincipal;
import com.unisage.backend.service.conversation.MessageService;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.guestsession.GuestSessionService.GuestSessionResolution;
import com.unisage.backend.service.ticket.TicketService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNISAGE-99 T21 against real Postgres (V34 partial unique indexes and CHECK) and the real HTTP
 * stack for the guest path (public route + guest cookie ownership).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CalculationFeedbackIntegrationTest {

    private static final String NOTE = "Quy chế 2024 đã đổi đơn giá";

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

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Autowired
    private CalculationFeedbackService calculationFeedbackService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private GuestSessionService guestSessionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private CalculationTraceService calculationTraceService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Message assistantWithItems(Conversation conversation) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("calculation", Map.of("schema_version", 1, "items", List.of(
                Map.of("item_id", "T1", "run_id", "req-1", "mode", "retrieved", "status", "computed",
                        "result_summary", "Học phí học kỳ: 8.400.000 đồng"),
                Map.of("item_id", "T2", "run_id", "req-1", "mode", "retrieved", "status", "computed"))));
        return messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("**Kết quả tham khảo theo quy chế**")
                .status(MsgStatus.COMPLETED)
                .metadata(metadata)
                .build());
    }

    private static CalculationFeedbackRequest wrong(String itemId) {
        return CalculationFeedbackRequest.builder().itemId(itemId).verdict(CalculationVerdict.WRONG)
                .reason(CalculationWrongReason.WRONG_RESULT).note(NOTE).build();
    }

    @Test
    void signedInUser_twoWrongItemsAndAReport_coexist_butASecondReportIsStillBlocked() {
        User user = userRepository.findAll().get(0);
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .user(user).title("calc").build());
        Message message = assistantWithItems(conversation);
        calculationTraceService.ingest(new CalculationTraceIngestRequest(message.getId(), List.of(
                new CalculationTraceIngestRequest.Item("T1", "req-1",
                        Map.of("question_raw", "Học phí 20 tín chỉ?", "expression", "so_tc * don_gia_tc")))));

        assertThat(calculationFeedbackService.submit(message.getId(), wrong("T1"), user.getId(), null)
                .ticketCreated()).isTrue();
        assertThat(calculationFeedbackService.submit(message.getId(), wrong("T2"), user.getId(), null)
                .ticketCreated()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UserPrincipal.builder().userId(user.getId()).build(), null, List.of()));
        CreateTicketRequest report = CreateTicketRequest.builder().messageId(message.getId())
                .type(TicketType.AI_UNANSWERED).title("Sai").description("Câu trả lời sai").build();
        TicketResponse created = ticketService.create(report);
        assertThatThrownBy(() -> ticketService.create(report)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TICKET_ALREADY_EXISTS));

        List<Ticket> tickets = ticketRepository.findAll().stream()
                .filter(t -> t.getMessage().getId().equals(message.getId())).toList();
        assertThat(tickets).hasSize(3);
        assertThat(tickets).extracting(Ticket::getCalculationItemId).containsExactlyInAnyOrder(null, "T1", "T2");
        Ticket t1 = tickets.stream().filter(t -> "T1".equals(t.getCalculationItemId())).findFirst().orElseThrow();
        assertThat(t1.getDescription()).contains(NOTE).contains("so_tc * don_gia_tc").contains("run_id: req-1");
        Ticket t2 = tickets.stream().filter(t -> "T2".equals(t.getCalculationItemId())).findFirst().orElseThrow();
        assertThat(t2.getDescription()).contains("Không có trace cho run_id req-1.");

        // The message list keeps showing only the Report's id, and never the note or the trace.
        MessageResponse listed = messageService.getByConversation(conversation.getId(), null, false).get(0);
        assertThat(listed.ticketId()).isEqualTo(created.id());
        assertThat(listed.metadata()).containsKey("calculation_feedback");
        assertThat(listed.toString()).doesNotContain(NOTE).doesNotContain("so_tc * don_gia_tc");
        assertThat(messageService.getById(message.getId()).toString())
                .doesNotContain(NOTE).doesNotContain("so_tc * don_gia_tc");
    }

    @Test
    void guest_overHttp_feedbackIsStoredWithoutTicket_andValidationErrorsAre400() {
        GuestSessionResolution guest = guestSessionService.resolveOrCreate(null);
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .guestSession(guest.session()).title("guest calc").build());
        Message message = assistantWithItems(conversation);
        String url = "http://localhost:" + port + "/api/v1/messages/" + message.getId() + "/calculation-feedback";

        ResponseEntity<String> ok = post(url, guest.rawToken(), """
                {"itemId": "T1", "verdict": "WRONG", "reason": "WRONG_FORMULA", "note": "%s"}
                """.formatted(NOTE));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).contains("\"code\":1000")
                .contains("\"itemId\":\"T1\"").contains("\"verdict\":\"WRONG\"")
                .contains("\"reason\":\"WRONG_FORMULA\"").contains("\"ticketCreated\":false");

        assertThat(post(url, guest.rawToken(), """
                {"itemId": "T1", "verdict": "MAYBE", "reason": null, "note": null}
                """).getStatusCode().value()).isEqualTo(400);
        assertThat(post(url, guest.rawToken(), """
                {"itemId": "T9", "verdict": "CORRECT", "reason": null, "note": null}
                """).getStatusCode().value()).isEqualTo(400);
        // Valid fields, but the body itself is over 2 KB.
        ResponseEntity<String> tooLarge = post(url, guest.rawToken(), """
                {"itemId": "T1", "verdict": "CORRECT", "reason": null, "note": null}%s
                """.formatted(" ".repeat(2100)));
        assertThat(tooLarge.getStatusCode().value()).isEqualTo(400);
        assertThat(tooLarge.getBody()).contains("\"code\":" + ErrorCode.CALCULATION_FEEDBACK_INVALID.getCode());
        // No (or someone else's) guest cookie: the message is not the caller's.
        assertThat(post(url, null, """
                {"itemId": "T1", "verdict": "CORRECT", "reason": null, "note": null}
                """).getStatusCode().value()).isEqualTo(404);

        String listed = restTemplate.getForEntity("http://localhost:" + port + "/api/v1/messages/conversation/"
                + conversation.getId(), String.class).getBody();
        assertThat(listed).contains("calculation_feedback").contains("WRONG_FORMULA").doesNotContain(NOTE);
        assertThat(ticketRepository.findAll().stream()
                .filter(t -> t.getMessage().getId().equals(message.getId()))).isEmpty();
    }

    private ResponseEntity<String> post(String url, String guestToken, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (guestToken != null) {
            headers.add(HttpHeaders.COOKIE, "guest_session_id=" + guestToken);
        }
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
