package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.service.usagelimit.UsageLimitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceImplTest {

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private ChatModelRepository chatModelRepository;
    private UsageLimitService usageLimitService;
    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        chatModelRepository = mock(ChatModelRepository.class);
        usageLimitService = mock(UsageLimitService.class);

        messageService = new MessageServiceImpl(
                messageRepository, conversationRepository, chatModelRepository, usageLimitService);
        ReflectionTestUtils.setField(messageService, "maxMessageHistory", 20);

        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));
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

        MessageResponse response = messageService.send(request, ownerId, "1.2.3.4");

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
        assertThatThrownBy(() -> messageService.send(request, intruderId, "1.2.3.4"))
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

        assertThatThrownBy(() -> messageService.send(request, null, "1.2.3.4"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_guestConversation_sameIp_succeeds() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId, null, "9.9.9.9");
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        MessageResponse response = messageService.send(request, null, "9.9.9.9");

        assertThat(response.content()).isEqualTo("hi");
    }

    @Test
    void send_guestConversation_differentIp_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId, null, "9.9.9.9");
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, null, "1.1.1.1"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    void send_guestConversation_loggedInCallerNotYetClaimed_throwsUnauthorized() {
        UUID conversationId = UUID.randomUUID();
        Conversation conversation = conversation(conversationId, null, "9.9.9.9");
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(conversationId)
                .role(MsgRole.USER)
                .content("hi")
                .build();

        assertThatThrownBy(() -> messageService.send(request, UUID.randomUUID(), "9.9.9.9"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Conversation conversation(UUID id) {
        return Conversation.builder().id(id).build();
    }

    private Conversation conversation(UUID id, User owner, String ipAddress) {
        return Conversation.builder().id(id).user(owner).ipAddress(ipAddress).build();
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
