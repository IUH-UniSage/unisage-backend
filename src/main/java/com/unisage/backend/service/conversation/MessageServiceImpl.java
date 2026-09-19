package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.usagelimit.UsageLimitService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ChatModelRepository chatModelRepository;
    private final UsageLimitService usageLimitService;
    private final GuestSessionService guestSessionService;
    private final TicketRepository ticketRepository;

    @Value("${app.message.max-history:20}")
    private int maxMessageHistory;

    @Override
    @Transactional
    public MessageResponse send(SendMessageRequest request, UUID callerId, String guestSessionToken) {
        Conversation conversation = conversationRepository.findById(request.conversationId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        validateOwnership(conversation, callerId, guestSessionToken);

        MsgStatus status = request.status() != null ? request.status() : MsgStatus.COMPLETED;
        if (status == MsgStatus.STREAMING && request.role() != MsgRole.ASSISTANT) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        if (status != MsgStatus.COMPLETED && status != MsgStatus.STREAMING) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        if (status == MsgStatus.COMPLETED && (request.content() == null || request.content().isBlank())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }

        ChatModel chatModel = null;
        if (request.chatModelId() != null) {
            chatModel = chatModelRepository.findById(request.chatModelId())
                    .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        }

        UsageLimit usageLimit = null;
        if (request.role() == MsgRole.USER) {
            usageLimit = usageLimitService.checkAndGetOrCreate(conversation.getUser(), conversation.getGuestSession());
        }

        Message message = Message.builder()
                .conversation(conversation)
                .role(request.role())
                .content(request.content() != null ? request.content() : "")
                .status(status)
                .chatModel(chatModel)
                .citations(request.citations())
                .retrievalScore(request.retrievalScore())
                .metadata(request.metadata())
                .build();
        message = messageRepository.save(message);

        usageLimitService.increment(usageLimit);

        return toResponse(message, ticketIdOf(message));
    }

    @Override
    @Transactional
    public MessageResponse update(UUID id, UpdateMessageRequest request) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));

        if (!message.getConversation().getId().equals(request.conversationId())) {
            throw new AppException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        if (message.getRole() != MsgRole.ASSISTANT) {
            throw new AppException(ErrorCode.MESSAGE_ROLE_NOT_ASSISTANT);
        }

        if (message.getStatus() == MsgStatus.COMPLETED || message.getStatus() == MsgStatus.ERROR) {
            boolean sameContent = message.getContent().equals(request.content());
            boolean sameStatus = message.getStatus() == request.status();
            if (sameContent && sameStatus) {
                return toResponse(message, ticketIdOf(message));
            }
            throw new AppException(ErrorCode.MESSAGE_CONTENT_CONFLICT);
        }

        if (message.getStatus() != MsgStatus.STREAMING
                || (request.status() != MsgStatus.COMPLETED && request.status() != MsgStatus.ERROR)) {
            throw new AppException(ErrorCode.MESSAGE_INVALID_STATUS_TRANSITION);
        }
        if (request.status() == MsgStatus.COMPLETED
                && (request.content() == null || request.content().isBlank())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }

        message.setContent(request.content() != null ? request.content() : "");
        message.setStatus(request.status());
        if (request.citations() != null) {
            message.setCitations(request.citations());
        }
        if (request.retrievalScore() != null) {
            message.setRetrievalScore(request.retrievalScore());
        }
        if (request.metadata() != null) {
            message.setMetadata(request.metadata());
        }

        message = messageRepository.save(message);
        return toResponse(message, ticketIdOf(message));
    }

    @Override
    public List<MessageResponse> getByConversation(UUID conversationId, Integer limit) {
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, maxMessageHistory) : maxMessageHistory;
        if (messages.size() > effectiveLimit) {
            messages = messages.subList(messages.size() - effectiveLimit, messages.size());
        }

        // One query for the whole page instead of one per message.
        Map<UUID, UUID> ticketIdByMessage = messages.isEmpty() ? Map.of()
                : ticketRepository.findByMessageIdIn(messages.stream().map(Message::getId).toList()).stream()
                        .collect(Collectors.toMap(t -> t.getMessage().getId(), Ticket::getId));

        return messages.stream()
                .map(message -> toResponse(message, ticketIdByMessage.get(message.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public MessageResponse getById(UUID id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));
        return toResponse(message, ticketIdOf(message));
    }

    /**
     * A caller may post into an owned conversation only as its owner, and into a guest
     * conversation (no owner yet) only by presenting the same guest session that created it —
     * mirrors the "valid guest" notion used by {@code ConversationServiceImpl#claim}. Resolving
     * the token here also slides its TTL forward (real chat activity), matching the refresh
     * policy documented on {@code GuestSessionService}.
     */
    private void validateOwnership(Conversation conversation, UUID callerId, String guestSessionToken) {
        if (conversation.getUser() != null) {
            if (callerId == null || !conversation.getUser().getId().equals(callerId)) {
                throw new AppException(ErrorCode.AUTH_UNAUTHORIZED);
            }
        } else {
            if (callerId != null) {
                throw new AppException(ErrorCode.AUTH_UNAUTHORIZED);
            }
            GuestSession resolved = guestSessionService.resolveAndTouch(guestSessionToken)
                    .map(GuestSessionService.GuestSessionResolution::session)
                    .orElse(null);
            if (resolved == null || conversation.getGuestSession() == null
                    || !resolved.getId().equals(conversation.getGuestSession().getId())) {
                throw new AppException(ErrorCode.AUTH_UNAUTHORIZED);
            }
        }
    }

    private UUID ticketIdOf(Message message) {
        return ticketRepository.findByMessageId(message.getId()).map(Ticket::getId).orElse(null);
    }

    private MessageResponse toResponse(Message message, UUID ticketId) {
        return MessageResponse.builder()
                .id(message.getId())
                .conversationId(message.getConversation().getId())
                .role(message.getRole())
                .content(message.getContent())
                .status(message.getStatus())
                .chatModelId(message.getChatModel() != null ? message.getChatModel().getId() : null)
                .citations(message.getCitations())
                .retrievalScore(message.getRetrievalScore())
                .metadata(message.getMetadata())
                .createdAt(message.getCreatedAt())
                .ticketId(ticketId)
                .build();
    }
}
