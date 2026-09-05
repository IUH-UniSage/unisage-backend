package com.unisage.backend.service.conversation;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
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

    // ── helpers ──────────────────────────────────────────────────────────

    private Conversation conversation(UUID id) {
        return Conversation.builder().id(id).build();
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
}
