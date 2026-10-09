package com.unisage.backend.service.calculation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.response.CalculationFeedbackResponse;
import com.unisage.backend.entity.CalculationTrace;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.entity.enums.CalculationWrongReason;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.CalculationTraceRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.guestsession.GuestSessionService.GuestSessionResolution;
import com.unisage.backend.service.ticket.TicketDescriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalculationFeedbackServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    private MessageRepository messageRepository;
    private TicketRepository ticketRepository;
    private CalculationTraceRepository calculationTraceRepository;
    private GuestSessionService guestSessionService;
    private CalculationFeedbackServiceImpl service;

    private final User owner = User.builder().id(UUID.randomUUID()).build();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        ticketRepository = mock(TicketRepository.class);
        calculationTraceRepository = mock(CalculationTraceRepository.class);
        guestSessionService = mock(GuestSessionService.class);
        service = new CalculationFeedbackServiceImpl(messageRepository, ticketRepository, calculationTraceRepository,
                guestSessionService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.findByMessageIdAndCalculationItemId(any(), any())).thenReturn(Optional.empty());
        when(calculationTraceRepository.findByMessageIdAndItemId(any(), any())).thenReturn(Optional.empty());
        when(guestSessionService.resolveAndTouch(any())).thenReturn(Optional.empty());
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static Map<String, Object> item(String itemId, String mode, String status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item_id", itemId);
        item.put("run_id", "run-" + itemId);
        item.put("mode", mode);
        item.put("status", status);
        item.put("result_summary", "Học phí học kỳ: 8.400.000 đồng");
        item.put("source_summary", Map.of("title", "QĐ-123.pdf", "heading", "Chương II › Điều 8"));
        return item;
    }

    private Message message(Conversation conversation, List<Map<String, Object>> items) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("calculation", Map.of("schema_version", 1, "items", items));
        metadata.put("other", "kept");
        Message message = Message.builder()
                .id(messageId)
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("answer")
                .status(MsgStatus.COMPLETED)
                .metadata(metadata)
                .build();
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));
        return message;
    }

    private Message ownedMessage() {
        return message(Conversation.builder().id(UUID.randomUUID()).user(owner).build(), List.of(
                item("T1", "retrieved", "computed"),
                item("T2", "retrieved", "computed"),
                item("T3", "builtin", "computed")));
    }

    private static CalculationFeedbackRequest wrong(String itemId, CalculationWrongReason reason, String note) {
        return CalculationFeedbackRequest.builder()
                .itemId(itemId).verdict(CalculationVerdict.WRONG).reason(reason).note(note).build();
    }

    private static CalculationFeedbackRequest correct(String itemId) {
        return CalculationFeedbackRequest.builder().itemId(itemId).verdict(CalculationVerdict.CORRECT).build();
    }

    private void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private Ticket existingTicket(TicketStatus status, String resolution) {
        Ticket ticket = Ticket.builder()
                .id(UUID.randomUUID()).user(owner).type(TicketType.AI_CALCULATION_WRONG).calculationItemId("T1")
                .status(status).title("Tính sai (T1): Sai kết quả").description("old").resolution(resolution)
                .build();
        when(ticketRepository.findByMessageIdAndCalculationItemId(messageId, "T1")).thenReturn(Optional.of(ticket));
        return ticket;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> feedbackOf(Message message, String itemId) {
        Map<String, Object> feedback = (Map<String, Object>) message.getMetadata().get("calculation_feedback");
        return feedback == null ? null : (Map<String, Object>) feedback.get(itemId);
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void wrongWithoutReason_isRejected() {
        ownedMessage();

        assertCode(() -> service.submit(messageId, wrong("T1", null, null), owner.getId(), null),
                ErrorCode.CALCULATION_FEEDBACK_INVALID);
        assertThat(ErrorCode.CALCULATION_FEEDBACK_INVALID.getHttpStatus().value()).isEqualTo(400);
    }

    @Test
    void correctWithReason_isRejected() {
        ownedMessage();
        CalculationFeedbackRequest request = CalculationFeedbackRequest.builder()
                .itemId("T1").verdict(CalculationVerdict.CORRECT).reason(CalculationWrongReason.WRONG_RESULT).build();

        assertCode(() -> service.submit(messageId, request, owner.getId(), null),
                ErrorCode.CALCULATION_FEEDBACK_INVALID);
    }

    @Test
    void otherWithoutNote_isRejected_blankCountsAsMissing() {
        ownedMessage();

        assertCode(() -> service.submit(messageId, wrong("T1", CalculationWrongReason.OTHER, null), owner.getId(),
                null), ErrorCode.CALCULATION_FEEDBACK_INVALID);
        assertCode(() -> service.submit(messageId, wrong("T1", CalculationWrongReason.OTHER, "   "), owner.getId(),
                null), ErrorCode.CALCULATION_FEEDBACK_INVALID);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void bodyOver2Kb_isRejected() {
        ownedMessage();
        // Bean validation is bypassed here; the service still refuses a request over 2 KB serialized.
        String note = "x".repeat(2100);

        assertCode(() -> service.submit(messageId, wrong("T1", CalculationWrongReason.OTHER, note), owner.getId(),
                null), ErrorCode.CALCULATION_FEEDBACK_INVALID);
    }

    // ── 404s ─────────────────────────────────────────────────────────────

    @Test
    void itemThatIsNotRetrievedAndComputed_isNotFound() {
        message(Conversation.builder().id(UUID.randomUUID()).user(owner).build(), List.of(
                item("T1", "builtin", "computed"),
                item("T2", "retrieved", "needs_input")));

        assertCode(() -> service.submit(messageId, correct("T1"), owner.getId(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        assertCode(() -> service.submit(messageId, correct("T2"), owner.getId(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        assertCode(() -> service.submit(messageId, correct("T3"), owner.getId(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        assertThat(ErrorCode.CALCULATION_ITEM_NOT_FOUND.getHttpStatus().value()).isEqualTo(404);
    }

    @Test
    void someoneElsesMessage_unknownMessage_orUserMessage_isNotFound() {
        Message message = ownedMessage();

        assertCode(() -> service.submit(messageId, correct("T1"), UUID.randomUUID(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        assertCode(() -> service.submit(messageId, correct("T1"), null, "guest-token"),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        message.setRole(MsgRole.USER);
        assertCode(() -> service.submit(messageId, correct("T1"), owner.getId(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.empty());
        assertCode(() -> service.submit(messageId, correct("T1"), owner.getId(), null),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
        assertThat(feedbackOf(message, "T1")).isNull();
    }

    // ── metadata ─────────────────────────────────────────────────────────

    @Test
    void feedback_isStoredWithoutTheNote_andKeepsOtherMetadata() {
        Message message = ownedMessage();

        service.submit(messageId, wrong("T1", CalculationWrongReason.WRONG_RESULT, "Quy chế 2024 đã đổi"),
                owner.getId(), null);

        assertThat(feedbackOf(message, "T1")).containsOnly(
                Map.entry("verdict", "WRONG"),
                Map.entry("reason", "WRONG_RESULT"),
                Map.entry("at", "2026-10-09T08:00:00Z"));
        assertThat(message.getMetadata()).containsKeys("calculation", "other");
        assertThat(message.getMetadata().toString()).doesNotContain("Quy chế 2024 đã đổi");
        verify(messageRepository).save(message);
    }

    @Test
    void correct_storesNullReason_andKeepsOtherItemsFeedback() {
        Message message = ownedMessage();
        service.submit(messageId, wrong("T2", CalculationWrongReason.WRONG_FORMULA, null), owner.getId(), null);

        CalculationFeedbackResponse response = service.submit(messageId, correct("T1"), owner.getId(), null);

        assertThat(response.ticketCreated()).isFalse();
        assertThat(response.reason()).isNull();
        assertThat(feedbackOf(message, "T1")).containsEntry("verdict", "CORRECT").containsEntry("reason", null);
        assertThat(feedbackOf(message, "T2")).containsEntry("verdict", "WRONG");
    }

    // ── tickets ──────────────────────────────────────────────────────────

    @Test
    void wrong_signedInUser_createsItemTicketWithTraceInDescription() {
        ownedMessage();
        when(calculationTraceRepository.findByMessageIdAndItemId(messageId, "T1")).thenReturn(Optional.of(
                CalculationTrace.builder().messageId(messageId).itemId("T1").runId("req-42").trace(Map.of(
                        "question_raw", "Học phí 20 tín chỉ?",
                        "expression", "so_tc * don_gia_tc",
                        "inputs", List.of(Map.of("name", "so_tc", "label", "Số tín chỉ", "value", "20")),
                        "source_quote", "Đơn giá 420.000 đồng/tín chỉ")).build()));

        CalculationFeedbackResponse response = service.submit(messageId,
                wrong("T1", CalculationWrongReason.WRONG_RESULT, "Quy chế 2024 đã đổi"), owner.getId(), null);

        ArgumentCaptor<Ticket> saved = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(saved.capture());
        Ticket ticket = saved.getValue();
        assertThat(response.ticketCreated()).isTrue();
        assertThat(response.itemId()).isEqualTo("T1");
        assertThat(ticket.getType()).isEqualTo(TicketType.AI_CALCULATION_WRONG);
        assertThat(ticket.getCalculationItemId()).isEqualTo("T1");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getUser()).isEqualTo(owner);
        assertThat(ticket.getTitle()).isEqualTo("Tính sai (T1): Sai kết quả");
        assertThat(ticket.getDescription())
                .contains("Lý do: Sai kết quả (WRONG_RESULT)")
                .contains("Ghi chú: Quy chế 2024 đã đổi")
                .contains("run_id: req-42")
                .contains(TicketDescriptions.STAFF_ONLY_MARKER)
                .contains("Câu hỏi gốc: Học phí 20 tín chỉ?")
                .contains("Biểu thức: so_tc * don_gia_tc")
                .contains("Số tín chỉ = 20")
                .contains("\"source_quote\" : \"Đơn giá 420.000 đồng/tín chỉ\"");
    }

    @Test
    void wrong_withoutTrace_saysSoWithTheRunId() {
        ownedMessage();

        service.submit(messageId, wrong("T1", CalculationWrongReason.WRONG_FORMULA, null), owner.getId(), null);

        ArgumentCaptor<Ticket> saved = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(saved.capture());
        assertThat(saved.getValue().getDescription())
                .contains("Ghi chú: (không có)")
                .contains("Không có trace cho run_id run-T1.");
    }

    @Test
    void wrongOnT1AndT2_createsTwoSeparateTickets() {
        ownedMessage();

        service.submit(messageId, wrong("T1", CalculationWrongReason.WRONG_RESULT, null), owner.getId(), null);
        service.submit(messageId, wrong("T2", CalculationWrongReason.MISSING_INFO, null), owner.getId(), null);

        ArgumentCaptor<Ticket> saved = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Ticket::getCalculationItemId).containsExactly("T1", "T2");
        assertThat(saved.getAllValues()).extracting(Ticket::getTitle)
                .containsExactly("Tính sai (T1): Sai kết quả", "Tính sai (T2): Thiếu thông tin");
    }

    @Test
    void wrongAgain_onOpenTicket_updatesReasonAndNote() {
        ownedMessage();
        Ticket ticket = existingTicket(TicketStatus.OPEN, null);

        CalculationFeedbackResponse response = service.submit(messageId,
                wrong("T1", CalculationWrongReason.OTHER, "Thiếu học phần tự chọn"), owner.getId(), null);

        assertThat(response.ticketCreated()).isTrue();
        assertThat(ticket.getTitle()).isEqualTo("Tính sai (T1): Khác");
        assertThat(ticket.getDescription()).contains("Lý do: Khác (OTHER)").contains("Thiếu học phần tự chọn");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void switchingToCorrect_closesTheOpenTicket() {
        Message message = ownedMessage();
        Ticket ticket = existingTicket(TicketStatus.OPEN, null);

        CalculationFeedbackResponse response = service.submit(messageId, correct("T1"), owner.getId(), null);

        assertThat(response.ticketCreated()).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(ticket.getResolution()).isEqualTo("Người dùng đổi đánh giá thành Đúng");
        assertThat(feedbackOf(message, "T1")).containsEntry("verdict", "CORRECT");
    }

    @Test
    void switchingBackToWrong_reopensTheTicketTheUserClosed() {
        ownedMessage();
        Ticket ticket = existingTicket(TicketStatus.CLOSED, CalculationFeedbackServiceImpl.USER_SWITCHED_TO_CORRECT);

        CalculationFeedbackResponse response = service.submit(messageId,
                wrong("T1", CalculationWrongReason.WRONG_SOURCE, null), owner.getId(), null);

        assertThat(response.ticketCreated()).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getResolution()).isNull();
        assertThat(ticket.getTitle()).isEqualTo("Tính sai (T1): Sai nguồn/quy chế");
    }

    @Test
    void ticketHandledByStaff_locksTheVerdict() {
        Message message = ownedMessage();
        existingTicket(TicketStatus.RESOLVED, "Đã sửa công thức");

        assertCode(() -> service.submit(messageId, correct("T1"), owner.getId(), null),
                ErrorCode.CALCULATION_FEEDBACK_LOCKED);
        assertCode(() -> service.submit(messageId, wrong("T1", CalculationWrongReason.WRONG_RESULT, null),
                owner.getId(), null), ErrorCode.CALCULATION_FEEDBACK_LOCKED);
        assertThat(ErrorCode.CALCULATION_FEEDBACK_LOCKED.getHttpStatus().value()).isEqualTo(409);
        assertThat(feedbackOf(message, "T1")).isNull();

        existingTicket(TicketStatus.CLOSED, "Không phải lỗi");
        assertCode(() -> service.submit(messageId, correct("T1"), owner.getId(), null),
                ErrorCode.CALCULATION_FEEDBACK_LOCKED);
    }

    @Test
    void guest_feedbackIsStored_butNoTicket() {
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        Message message = message(Conversation.builder().id(UUID.randomUUID()).guestSession(session).build(),
                List.of(item("T1", "retrieved", "computed")));
        when(guestSessionService.resolveAndTouch("guest-token"))
                .thenReturn(Optional.of(new GuestSessionResolution(session, "guest-token")));

        CalculationFeedbackResponse response = service.submit(messageId,
                wrong("T1", CalculationWrongReason.WRONG_RESULT, null), null, "guest-token");

        assertThat(response.ticketCreated()).isFalse();
        assertThat(feedbackOf(message, "T1")).containsEntry("verdict", "WRONG");
        verify(ticketRepository, never()).save(any());
        verify(ticketRepository, never()).findByMessageIdAndCalculationItemId(any(), any());
    }

    @Test
    void guest_withAnotherSession_isNotFound() {
        GuestSession session = GuestSession.builder().id(UUID.randomUUID()).build();
        message(Conversation.builder().id(UUID.randomUUID()).guestSession(session).build(),
                List.of(item("T1", "retrieved", "computed")));
        when(guestSessionService.resolveAndTouch("other-token")).thenReturn(Optional.of(
                new GuestSessionResolution(GuestSession.builder().id(UUID.randomUUID()).build(), "other-token")));

        assertCode(() -> service.submit(messageId, correct("T1"), null, "other-token"),
                ErrorCode.CALCULATION_ITEM_NOT_FOUND);
    }
}
