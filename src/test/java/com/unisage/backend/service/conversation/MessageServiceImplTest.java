package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.guestsession.GuestSessionService.GuestSessionResolution;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.service.usagelimit.UsageLimitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageServiceImplTest {

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private ChatModelRepository chatModelRepository;
    private UsageLimitService usageLimitService;
    private GuestSessionService guestSessionService;
    private TicketRepository ticketRepository;
    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        chatModelRepository = mock(ChatModelRepository.class);
        usageLimitService = mock(UsageLimitService.class);
        guestSessionService = mock(GuestSessionService.class);
        ticketRepository = mock(TicketRepository.class);
        SystemConfigResolver configResolver = mock(SystemConfigResolver.class);
        when(configResolver.getInt(any(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        messageService = new MessageServiceImpl(
                messageRepository, conversationRepository, chatModelRepository, usageLimitService,
                guestSessionService, ticketRepository, configResolver);

        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(guestSessionService.resolveAndTouch(any())).thenReturn(Optional.empty());
    }

    // ── update() ─────────────────────────────────────────────────────────

    @Test
    void update_conversationMismatch_throwsMessageNotFound() {
        UUID messageId = UUID.randomUUID();
        Conversation conversation = conversation(UUID.randomUUID());
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(UUID.randomUUID()) // different from message's conversation
                .content("final answer")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void update_userRoleMessage_throwsRoleNotAssistant() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = Message.builder()
                .id(messageId)
                .conversation(conversation)
                .role(MsgRole.USER)
                .content("hello")
                .status(MsgStatus.COMPLETED)
                .build();
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("hello")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_ROLE_NOT_ASSISTANT);
    }

    @Test
    void update_streamingToCompleted_succeeds() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("final answer")
                .status(MsgStatus.COMPLETED)
                .build();

        MessageResponse response = messageService.update(messageId, request);

        assertThat(response.content()).isEqualTo("final answer");
        assertThat(response.status()).isEqualTo(MsgStatus.COMPLETED);
    }

    @Test
    void update_streamingToError_succeeds() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("something went wrong")
                .status(MsgStatus.ERROR)
                .build();

        MessageResponse response = messageService.update(messageId, request);

        assertThat(response.status()).isEqualTo(MsgStatus.ERROR);
    }

    @Test
    void update_streamingToErrorWithBlankContent_succeeds() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("")
                .status(MsgStatus.ERROR)
                .build();

        MessageResponse response = messageService.update(messageId, request);

        assertThat(response.status()).isEqualTo(MsgStatus.ERROR);
        assertThat(response.content()).isEmpty();
    }

    @Test
    void update_streamingToCompletedWithBlankContent_throwsValidationError() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void update_streamingToPending_throwsInvalidTransition() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("partial")
                .status(MsgStatus.PENDING)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_INVALID_STATUS_TRANSITION);
    }

    @Test
    void update_pendingMessage_throwsInvalidTransition() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.PENDING, "");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("final")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_INVALID_STATUS_TRANSITION);
    }

    @Test
    void update_alreadyCompletedWithSameContentAndStatus_isIdempotentNoOp() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.COMPLETED, "final answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("final answer")
                .status(MsgStatus.COMPLETED)
                .build();

        MessageResponse response = messageService.update(messageId, request);

        assertThat(response.content()).isEqualTo("final answer");
        assertThat(response.status()).isEqualTo(MsgStatus.COMPLETED);
    }

    @Test
    void update_alreadyCompletedWithDifferentContent_throwsConflict() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.COMPLETED, "final answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("a different answer")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_CONTENT_CONFLICT);
    }

    @Test
    void update_alreadyErrorWithDifferentContent_throwsConflict() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.ERROR, "boom");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(conversationId)
                .content("a different error")
                .status(MsgStatus.ERROR)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_CONTENT_CONFLICT);
    }

    @Test
    void update_missingMessage_throwsMessageNotFound() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        UpdateMessageRequest request = UpdateMessageRequest.builder()
                .conversationId(UUID.randomUUID())
                .content("x")
                .status(MsgStatus.COMPLETED)
                .build();

        assertThatThrownBy(() -> messageService.update(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_NOT_FOUND);
    }

    // ── getByConversation() limit ceiling ───────────────────────────────

    @Test
    void getByConversation_noLimit_capsAtMaxHistory() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, null);

        assertThat(result).hasSize(20);
        // keeps the most recent ones (tail of the ascending list)
        assertThat(result.get(result.size() - 1).content()).isEqualTo("msg-24");
    }

    @Test
    void getByConversation_limitBelowCeiling_isRespected() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 5);

        assertThat(result).hasSize(5);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("msg-24");
    }

    @Test
    void getByConversation_limitAboveCeiling_isCappedAtMaxHistory() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 1000);

        assertThat(result).hasSize(20);
    }

    @Test
    void getByConversation_fewerMessagesThanLimit_returnsAll() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 3);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 10);

        assertThat(result).hasSize(3);
    }

    @Test
    void getByConversation_marksOnlyReportedMessagesWithTheirTicketId() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 3);
        UUID reportedMessageId = messages.get(1).getId();
        UUID ticketId = UUID.randomUUID();
        Ticket ticket = Ticket.builder().id(ticketId).message(messages.get(1)).build();
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);
        when(ticketRepository.findByMessageIdIn(any())).thenReturn(List.of(ticket));

        List<MessageResponse> result = messageService.getByConversation(conversationId, 10);

        assertThat(result).extracting(MessageResponse::ticketId).containsExactly(null, ticketId, null);
        assertThat(result.get(1).id()).isEqualTo(reportedMessageId);
    }

    @Test
    void getById_includesTicketIdWhenTheMessageWasReported() {
        UUID messageId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).build();
        Message message = assistantMessage(messageId, conversation, MsgStatus.COMPLETED, "answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(ticketRepository.findByMessageId(messageId))
                .thenReturn(Optional.of(Ticket.builder().id(ticketId).build()));

        assertThat(messageService.getById(messageId).ticketId()).isEqualTo(ticketId);
    }

    @Test
    void getById_ticketIdIsNullWhenNotReported() {
        UUID messageId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).build();
        Message message = assistantMessage(messageId, conversation, MsgStatus.COMPLETED, "answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(ticketRepository.findByMessageId(messageId)).thenReturn(Optional.empty());

        assertThat(messageService.getById(messageId).ticketId()).isNull();
    }

    // ── usage limit hooks ────────────────────────────────────────────────

    @Test
    void send_userMessage_checksAndCountsQuestionBeforeSaving() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        messageService.send(SendMessageRequest.builder()
                .conversationId(conversationId).role(MsgRole.USER).content("hi").build(), owner.getId(), null);

        verify(usageLimitService).checkAndConsumeQuestion(owner, null, "hi");
        verify(usageLimitService, never()).consumeAnswer(any(), any(), any());
    }

    @Test
    void send_userMessageOverLimit_propagatesAndSavesNothing() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        doThrow(new AppException(ErrorCode.USAGE_LIMIT_EXCEEDED))
                .when(usageLimitService).checkAndConsumeQuestion(any(), any(), any());

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId).role(MsgRole.USER).content("hi").build();

        assertThatThrownBy(() -> messageService.send(request, owner.getId(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.USAGE_LIMIT_EXCEEDED);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void send_streamingAssistantPlaceholder_isNotCounted() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        messageService.send(SendMessageRequest.builder()
                .conversationId(conversationId).role(MsgRole.ASSISTANT).status(MsgStatus.STREAMING).content("")
                .build(), owner.getId(), null);

        verify(usageLimitService, never()).checkAndConsumeQuestion(any(), any(), any());
        verify(usageLimitService, never()).consumeAnswer(any(), any(), any());
    }

    @Test
    void send_completedAssistantMessage_countsAnswer() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        messageService.send(SendMessageRequest.builder()
                .conversationId(conversationId).role(MsgRole.ASSISTANT).status(MsgStatus.COMPLETED)
                .content("answer").build(), owner.getId(), null);

        verify(usageLimitService).consumeAnswer(owner, null, "answer");
    }

    @Test
    void update_streamingToCompleted_countsAnswerOfFinalContent() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        messageService.update(messageId, UpdateMessageRequest.builder()
                .conversationId(conversationId).content("final answer").status(MsgStatus.COMPLETED).build());

        verify(usageLimitService).consumeAnswer(owner, null, "final answer");
    }

    @Test
    void update_streamingToError_doesNotCountAnswer() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId);
        Message message = assistantMessage(messageId, conversation, MsgStatus.STREAMING, "partial");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        messageService.update(messageId, UpdateMessageRequest.builder()
                .conversationId(conversationId).content("oops").status(MsgStatus.ERROR).build());

        verify(usageLimitService, never()).consumeAnswer(any(), any(), any());
    }

    // ── send() ownership ─────────────────────────────────────────────────

    @Test
    void send_ownedConversation_callerIsOwner_succeeds() {
        UUID conversationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        MessageResponse response = messageService.send(request, ownerId, null);

        assertThat(response.content()).isEqualTo("hi");
    }

    @Test
    void send_ownedConversation_callerIsDifferentUser_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        UUID intruderId = UUID.randomUUID();
        assertThatThrownBy(() -> messageService.send(request, intruderId, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_ownedConversation_guestCaller_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        Conversation conversation = conversation(conversationId, owner, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, null, "some-token"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_guestConversation_sameSession_succeeds() {
        UUID conversationId = UUID.randomUUID();
        GuestSession session = guestSession(UUID.randomUUID());
        Conversation conversation = conversation(conversationId, null, session);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(guestSessionService.resolveAndTouch("token-a"))
                .thenReturn(Optional.of(new GuestSessionResolution(session, "token-a")));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        MessageResponse response = messageService.send(request, null, "token-a");

        assertThat(response.content()).isEqualTo("hi");
    }

    @Test
    void send_guestConversation_differentSession_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        GuestSession ownerSession = guestSession(UUID.randomUUID());
        GuestSession callerSession = guestSession(UUID.randomUUID());
        Conversation conversation = conversation(conversationId, null, ownerSession);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(guestSessionService.resolveAndTouch("token-b"))
                .thenReturn(Optional.of(new GuestSessionResolution(callerSession, "token-b")));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, null, "token-b"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_guestConversation_missingOrInvalidCookie_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        GuestSession ownerSession = guestSession(UUID.randomUUID());
        Conversation conversation = conversation(conversationId, null, ownerSession);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        // no stubbing for resolveAndTouch(null) -> falls back to the default Optional.empty() stub

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, null, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_guestConversation_loggedInCallerNotYetClaimed_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        GuestSession session = guestSession(UUID.randomUUID());
        Conversation conversation = conversation(conversationId, null, session);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(guestSessionService.resolveAndTouch("token-a"))
                .thenReturn(Optional.of(new GuestSessionResolution(session, "token-a")));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, UUID.randomUUID(), "token-a"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Conversation conversation(UUID id) {
        return Conversation.builder().id(id).build();
    }

    private Conversation conversation(UUID id, User owner, GuestSession guestSession) {
        return Conversation.builder().id(id).user(owner).guestSession(guestSession).build();
    }

    private GuestSession guestSession(UUID id) {
        return GuestSession.builder().id(id).build();
    }

    private Message assistantMessage(UUID id, Conversation conversation, MsgStatus status, String content) {
        return Message.builder()
                .id(id)
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content(content)
                .status(status)
                .build();
    }

    private List<Message> buildMessages(UUID conversationId, int count) {
        Conversation conversation = conversation(conversationId);
        List<Message> messages = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Message m = Message.builder()
                    .id(UUID.randomUUID())
                    .conversation(conversation)
                    .role(MsgRole.USER)
                    .content("msg-" + i)
                    .status(MsgStatus.COMPLETED)
                    .build();
            m.setCreatedAt(LocalDateTime.now().plusSeconds(i));
            messages.add(m);
        }
        return messages;
    }
}
