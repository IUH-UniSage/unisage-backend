package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.StartTurnRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.request.internal.CancelClarificationRequest;
import com.unisage.backend.dto.response.ChatTurnResponse;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.dto.response.internal.ClarificationStatusResponse;
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
import static org.mockito.Mockito.times;
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

    // ── getByConversation() limits ───────────────────────────────────────

    @Test
    void getByConversation_uiWithoutLimit_returnsWholeHistory() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, null, false);

        assertThat(result).hasSize(25);
    }

    @Test
    void getByConversation_uiWithLimit_returnsLatestMessages() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 22, false);

        assertThat(result).hasSize(22);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("msg-24");
    }

    @Test
    void getByConversation_contextWithoutLimit_capsAtMaxHistory() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, null, true);

        assertThat(result).hasSize(20);
        // keeps the most recent ones (tail of the ascending list)
        assertThat(result.get(result.size() - 1).content()).isEqualTo("msg-24");
    }

    @Test
    void getByConversation_contextLimitBelowCeiling_isRespected() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 5, true);

        assertThat(result).hasSize(5);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("msg-24");
    }

    @Test
    void getByConversation_contextLimitAboveCeiling_isCappedAtMaxHistory() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 25);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 1000, true);

        assertThat(result).hasSize(20);
    }

    @Test
    void getByConversation_fewerMessagesThanLimit_returnsAll() {
        UUID conversationId = UUID.randomUUID();
        List<Message> messages = buildMessages(conversationId, 3);
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(messages);

        List<MessageResponse> result = messageService.getByConversation(conversationId, 10, true);

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
        when(ticketRepository.findByMessageIdInAndCalculationItemIdIsNull(any())).thenReturn(List.of(ticket));

        List<MessageResponse> result = messageService.getByConversation(conversationId, 10, false);

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
        when(ticketRepository.findByMessageIdAndCalculationItemIdIsNull(messageId))
                .thenReturn(Optional.of(Ticket.builder().id(ticketId).build()));

        assertThat(messageService.getById(messageId).ticketId()).isEqualTo(ticketId);
    }

    @Test
    void getById_ticketIdIsNullWhenNotReported() {
        UUID messageId = UUID.randomUUID();
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).build();
        Message message = assistantMessage(messageId, conversation, MsgStatus.COMPLETED, "answer");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
        when(ticketRepository.findByMessageIdAndCalculationItemIdIsNull(messageId)).thenReturn(Optional.empty());

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

    // ── startTurn() ──────────────────────────────────────────────────────

    @Test
    void startTurn_savesUserThenAssistantPlaceholder() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());

        ChatTurnResponse response = messageService.startTurn(
                new StartTurnRequest(conversationId, "hi"), owner.getId(), null);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Message::getRole, Message::getStatus, Message::getContent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MsgRole.USER, MsgStatus.COMPLETED, "hi"),
                        org.assertj.core.groups.Tuple.tuple(MsgRole.ASSISTANT, MsgStatus.STREAMING, ""));
        assertThat(response.firstTurn()).isTrue();
        assertThat(response.context()).isEmpty();
        assertThat(response.userMessage().role()).isEqualTo(MsgRole.USER);
        assertThat(response.assistantMessage().role()).isEqualTo(MsgRole.ASSISTANT);
        verify(usageLimitService).checkAndConsumeQuestion(owner, null, "hi");
    }

    @Test
    void startTurn_assistantPlaceholderIsStampedStrictlyAfterUserMessage() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setCreatedAt(LocalDateTime.now());
            return message;
        });

        for (int i = 0; i < 200; i++) {
            ChatTurnResponse response = messageService.startTurn(
                    new StartTurnRequest(conversationId, "hi"), owner.getId(), null);

            LocalDateTime user = response.userMessage().createdAt().truncatedTo(ChronoUnit.MICROS);
            LocalDateTime assistant = response.assistantMessage().createdAt().truncatedTo(ChronoUnit.MICROS);
            assertThat(assistant).isAfter(user.plus(1, ChronoUnit.MICROS));
        }
    }

    @Test
    void startTurn_existingConversation_returnsCappedContextAndNotFirstTurn() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(buildMessages(conversationId, 25));
        when(ticketRepository.findByMessageIdInAndCalculationItemIdIsNull(any())).thenReturn(List.of());

        ChatTurnResponse response = messageService.startTurn(
                new StartTurnRequest(conversationId, "hi"), owner.getId(), null);

        assertThat(response.firstTurn()).isFalse();
        assertThat(response.context()).hasSize(20);
        assertThat(response.context().get(19).content()).isEqualTo("msg-24");
    }

    @Test
    void startTurn_overLimit_propagatesAndSavesNothing() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());
        doThrow(new AppException(ErrorCode.USAGE_LIMIT_EXCEEDED))
                .when(usageLimitService).checkAndConsumeQuestion(any(), any(), any());

        StartTurnRequest request = new StartTurnRequest(conversationId, "hi");

        assertThatThrownBy(() -> messageService.startTurn(request, owner.getId(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.USAGE_LIMIT_EXCEEDED);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void startTurn_callerIsNotOwner_throwsUnauthorizedAndSavesNothing() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));

        StartTurnRequest request = new StartTurnRequest(conversationId, "hi");

        assertThatThrownBy(() -> messageService.startTurn(request, UUID.randomUUID(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void startTurn_withClarificationAnswers_storesThemOnTheUserMessageOnly() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());
        Map<String, Object> metadata = Map.of("clarification_answers", Map.of(
                "schema_version", 1,
                "panel_id", "7f1c2a9e",
                "items", List.of(Map.of("question_id", "q1", "kind", "choice", "display", "K20"))));

        ChatTurnResponse response = messageService.startTurn(
                new StartTurnRequest(conversationId, "Khoá: K20", metadata), owner.getId(), null);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getMetadata()).isEqualTo(metadata);
        assertThat(saved.getAllValues().get(1).getMetadata()).isNull();
        assertThat(response.userMessage().metadata()).isEqualTo(metadata);
    }

    @Test
    void startTurn_withoutMetadata_leavesUserMessageMetadataNull() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());

        messageService.startTurn(new StartTurnRequest(conversationId, "hi", null), owner.getId(), null);

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Message::getMetadata).containsOnlyNulls();
    }

    @Test
    void startTurn_unknownMetadataKey_isRejectedBeforeAnythingIsSavedOrCounted() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        Map<String, Object> metadata = Map.of("clarification_answers", Map.of(), "calculation", Map.of());

        StartTurnRequest request = new StartTurnRequest(conversationId, "hi", metadata);

        assertThatThrownBy(() -> messageService.startTurn(request, owner.getId(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_METADATA_INVALID);
        assertThat(ErrorCode.MESSAGE_METADATA_INVALID.getHttpStatus().value()).isEqualTo(400);
        verify(messageRepository, never()).save(any(Message.class));
        verify(usageLimitService, never()).checkAndConsumeQuestion(any(), any(), any());
    }

    @Test
    void startTurn_metadataOver32Kb_isRejected() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        Map<String, Object> metadata = Map.of("clarification_answers", Map.of("blob", "x".repeat(32 * 1024)));

        StartTurnRequest request = new StartTurnRequest(conversationId, "hi", metadata);

        assertThatThrownBy(() -> messageService.startTurn(request, owner.getId(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_METADATA_INVALID);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void startTurn_metadataJustUnder32Kb_isAccepted() {
        UUID conversationId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).build();
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation(conversationId, owner, null)));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());
        // {"clarification_answers":{"blob":"..."}} adds 37 bytes of JSON around the string: exactly 32 KB.
        Map<String, Object> metadata = Map.of("clarification_answers", Map.of("blob", "x".repeat(32 * 1024 - 37)));

        ChatTurnResponse response = messageService.startTurn(
                new StartTurnRequest(conversationId, "hi", metadata), owner.getId(), null);

        assertThat(response.userMessage().metadata()).isEqualTo(metadata);
    }

    // ── cancelClarification() ────────────────────────────────────────────

    @Test
    void cancelClarification_openPanel_flipsOnlyTheStatusAndKeepsEverythingElse() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(conversationId), "open");
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        ClarificationStatusResponse response = messageService.cancelClarification(
                messageId, new CancelClarificationRequest(conversationId, "cancelled"));

        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.messageId()).isEqualTo(messageId);
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) message.getMetadata().get("clarification");
        assertThat(clarification)
                .containsEntry("status", "cancelled")
                .containsEntry("schema_version", 1)
                .containsEntry("panel", Map.of("panel_id", "p-1"));
        assertThat(message.getMetadata()).containsEntry("other", "kept");
        assertThat(message.getContent()).isEqualTo("Bạn thuộc khoá nào?");
        assertThat(message.getStatus()).isEqualTo(MsgStatus.COMPLETED);
        verify(messageRepository).save(message);
        verify(usageLimitService, never()).consumeAnswer(any(), any(), any());
    }

    @Test
    void cancelClarification_alreadyCancelled_isIdempotent() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(conversationId), "cancelled");
        Map<String, Object> before = message.getMetadata();
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        ClarificationStatusResponse response = messageService.cancelClarification(
                messageId, new CancelClarificationRequest(conversationId, "cancelled"));

        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(message.getMetadata()).isSameAs(before);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void cancelClarification_otherTargetStatus_isConflict() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(conversationId), "cancelled");
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        assertCancelFails(messageId, new CancelClarificationRequest(conversationId, "open"),
                ErrorCode.CLARIFICATION_INVALID_STATUS_TRANSITION);
        assertThat(ErrorCode.CLARIFICATION_INVALID_STATUS_TRANSITION.getHttpStatus().value()).isEqualTo(409);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    void cancelClarification_fromUnknownCurrentStatus_isConflict() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(conversationId), "answered");
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        assertCancelFails(messageId, new CancelClarificationRequest(conversationId, "cancelled"),
                ErrorCode.CLARIFICATION_INVALID_STATUS_TRANSITION);
    }

    @Test
    void cancelClarification_wrongConversation_isNotFound() {
        UUID messageId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(UUID.randomUUID()), "open");
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        assertCancelFails(messageId, new CancelClarificationRequest(UUID.randomUUID(), "cancelled"),
                ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void cancelClarification_userMessage_isNotFound() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = panelMessage(messageId, conversation(conversationId), "open");
        message.setRole(MsgRole.USER);
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        assertCancelFails(messageId, new CancelClarificationRequest(conversationId, "cancelled"),
                ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void cancelClarification_messageWithoutPanel_isNotFound() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message message = assistantMessage(messageId, conversation(conversationId), MsgStatus.COMPLETED, "answer");
        message.setMetadata(Map.of("calculation", Map.of()));
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.of(message));

        assertCancelFails(messageId, new CancelClarificationRequest(conversationId, "cancelled"),
                ErrorCode.MESSAGE_NOT_FOUND);
    }

    @Test
    void cancelClarification_unknownMessage_isNotFound() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdForUpdate(messageId)).thenReturn(Optional.empty());

        assertCancelFails(messageId, new CancelClarificationRequest(UUID.randomUUID(), "cancelled"),
                ErrorCode.MESSAGE_NOT_FOUND);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void assertCancelFails(UUID messageId, CancelClarificationRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> messageService.cancelClarification(messageId, request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private Message panelMessage(UUID id, Conversation conversation, String status) {
        Message message = assistantMessage(id, conversation, MsgStatus.COMPLETED, "Bạn thuộc khoá nào?");
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("schema_version", 1);
        clarification.put("status", status);
        clarification.put("panel", Map.of("panel_id", "p-1"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("clarification", clarification);
        metadata.put("other", "kept");
        message.setMetadata(metadata);
        return message;
    }

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
