package com.unisage.backend.service.ticket;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.unisage.backend.dto.request.CreateTicketRequest;
import com.unisage.backend.dto.request.UpdateTicketRequest;
import com.unisage.backend.dto.response.TicketDetailResponse;
import com.unisage.backend.dto.response.TicketResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.TicketType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketServiceImplTest {

    private final UUID callerId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    private TicketRepository ticketRepository;
    private MessageRepository messageRepository;
    private UserRepository userRepository;
    private TicketServiceImpl service;
    private User caller;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        messageRepository = mock(MessageRepository.class);
        userRepository = mock(UserRepository.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        when(securityUtil.getCurrentUserId()).thenReturn(callerId);
        service = new TicketServiceImpl(ticketRepository, messageRepository, userRepository, securityUtil);

        caller = User.builder().id(callerId).email("a@b.c").firstName("A").lastName("B").build();
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository
                .findFirstByConversationIdAndRoleAndCreatedAtBeforeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private Message message(MsgRole role, User conversationOwner) {
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).user(conversationOwner).build();
        return Message.builder().id(messageId).conversation(conversation).role(role).content("answer").build();
    }

    private CreateTicketRequest createRequest() {
        return CreateTicketRequest.builder()
                .messageId(messageId).type(TicketType.AI_UNANSWERED).title("  Sai  ").description("Mô tả").build();
    }

    private Ticket ticket(TicketStatus status, String resolution) {
        return Ticket.builder()
                .id(UUID.randomUUID()).user(caller).message(message(MsgRole.ASSISTANT, caller))
                .type(TicketType.OTHER).status(status).title("t").description("d").resolution(resolution).build();
    }

    private void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ── create ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void create_savesOpenTicketForCallerAndTrimsText() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.ASSISTANT, caller)));

        TicketResponse response = service.create(createRequest());

        assertThat(response.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(response.title()).isEqualTo("Sai");
        assertThat(response.userId()).isEqualTo(callerId);
        assertThat(response.messageId()).isEqualTo(messageId);
    }

    @Test
    void create_rejectsUnknownMessage() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_MESSAGE_INVALID);
    }

    @Test
    void create_rejectsMessageInSomeoneElsesConversation() {
        User other = User.builder().id(UUID.randomUUID()).build();
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.ASSISTANT, other)));

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_MESSAGE_INVALID);
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_rejectsGuestConversation() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.ASSISTANT, null)));

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_MESSAGE_INVALID);
    }

    @Test
    void create_rejectsNonAssistantMessage() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.USER, caller)));

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_MESSAGE_INVALID);
    }

    @Test
    void create_rejectsSecondTicketForSameMessage() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.ASSISTANT, caller)));
        when(ticketRepository.existsByMessageId(messageId)).thenReturn(true);

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_ALREADY_EXISTS);
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_mapsConcurrentDuplicateToAlreadyExists() {
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message(MsgRole.ASSISTANT, caller)));
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertCode(() -> service.create(createRequest()), ErrorCode.TICKET_ALREADY_EXISTS);
    }

    // ── my tickets ─────────────────────────────────────────────────────────────────────────────

    @Test
    void getMyTicket_notFoundWhenItBelongsToSomeoneElse() {
        UUID id = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(id, callerId)).thenReturn(Optional.empty());

        assertCode(() -> service.getMyTicket(id), ErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    void getMyTicket_includesQuestionAndAnswer() {
        Ticket t = ticket(TicketStatus.OPEN, null);
        when(ticketRepository.findByIdAndUserId(t.getId(), callerId)).thenReturn(Optional.of(t));
        when(messageRepository
                .findFirstByConversationIdAndRoleAndCreatedAtBeforeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(Message.builder().content("câu hỏi").build()));

        TicketDetailResponse detail = service.getMyTicket(t.getId());

        assertThat(detail.questionContent()).isEqualTo("câu hỏi");
        assertThat(detail.messageContent()).isEqualTo("answer");
    }

    // ── update (admin) ─────────────────────────────────────────────────────────────────────────

    @Test
    void update_movesStatusAndStoresResolution() {
        Ticket t = ticket(TicketStatus.OPEN, null);
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        TicketDetailResponse detail = service.update(t.getId(),
                UpdateTicketRequest.builder().status(TicketStatus.RESOLVED).resolution("  Đã xử lý  ").build());

        assertThat(detail.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(detail.resolution()).isEqualTo("Đã xử lý");
    }

    @Test
    void update_resolvedNeedsAResolution() {
        Ticket t = ticket(TicketStatus.PROCESSING, null);
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertCode(() -> service.update(t.getId(),
                UpdateTicketRequest.builder().status(TicketStatus.RESOLVED).resolution("   ").build()),
                ErrorCode.TICKET_RESOLUTION_REQUIRED);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.PROCESSING);
    }

    @Test
    void update_resolvedKeepsAnExistingResolution() {
        Ticket t = ticket(TicketStatus.PROCESSING, "đã ghi trước");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        TicketDetailResponse detail = service.update(t.getId(),
                UpdateTicketRequest.builder().status(TicketStatus.RESOLVED).build());

        assertThat(detail.resolution()).isEqualTo("đã ghi trước");
    }

    @Test
    void update_closedIsTerminal() {
        Ticket t = ticket(TicketStatus.CLOSED, "xong");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertCode(() -> service.update(t.getId(),
                UpdateTicketRequest.builder().status(TicketStatus.OPEN).build()),
                ErrorCode.TICKET_INVALID_STATUS_TRANSITION);
    }

    @Test
    void update_sameStatusStillLetsStaffEditTheResolution() {
        Ticket t = ticket(TicketStatus.RESOLVED, "cũ");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        TicketDetailResponse detail = service.update(t.getId(),
                UpdateTicketRequest.builder().status(TicketStatus.RESOLVED).resolution("mới").build());

        assertThat(detail.resolution()).isEqualTo("mới");
    }
}
