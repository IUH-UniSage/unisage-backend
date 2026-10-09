package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.StartTurnRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.request.internal.CancelClarificationRequest;
import com.unisage.backend.dto.response.ChatTurnResponse;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.dto.response.internal.ClarificationStatusResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.Ticket;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.service.usagelimit.UsageLimitService;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    // created_at is rounded to microseconds by Postgres; 2us keeps the next row strictly after.
    private static final long CREATED_AT_GAP_NANOS = 2_000;

    /** SPEC-clarification-panel §7.1: the only USER-message metadata key a turn may carry. */
    private static final Set<String> ALLOWED_TURN_METADATA_KEYS = Set.of("clarification_answers");
    private static final int MAX_TURN_METADATA_BYTES = 32 * 1024;

    private static final String CLARIFICATION_KEY = "clarification";
    private static final String CLARIFICATION_OPEN = "open";
    private static final String CLARIFICATION_CANCELLED = "cancelled";

    // Only used to measure the serialized size of client-supplied metadata.
    private static final ObjectMapper SIZE_MAPPER = new ObjectMapper();

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ChatModelRepository chatModelRepository;
    private final UsageLimitService usageLimitService;
    private final GuestSessionService guestSessionService;
    private final TicketRepository ticketRepository;
    private final SystemConfigResolver configResolver;

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

        if (request.role() == MsgRole.USER) {
            usageLimitService.checkAndConsumeQuestion(
                    conversation.getUser(), conversation.getGuestSession(), request.content());
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

        if (request.role() == MsgRole.ASSISTANT && status == MsgStatus.COMPLETED) {
            usageLimitService.consumeAnswer(conversation.getUser(), conversation.getGuestSession(), message.getContent());
        }

        return toResponse(message, ticketIdOf(message));
    }

    @Override
    @Transactional
    public ChatTurnResponse startTurn(StartTurnRequest request, UUID callerId, String guestSessionToken) {
        Map<String, Object> userMetadata = validateTurnMetadata(request.metadata());

        Conversation conversation = conversationRepository.findById(request.conversationId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        validateOwnership(conversation, callerId, guestSessionToken);

        List<Message> previous = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List<MessageResponse> context = toResponses(previous, null, true);

        usageLimitService.checkAndConsumeQuestion(
                conversation.getUser(), conversation.getGuestSession(), request.content());

        Message userMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.USER)
                .content(request.content())
                .status(MsgStatus.COMPLETED)
                .metadata(userMetadata)
                .build());
        waitPastCreatedAt(userMessage);
        Message assistantMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("")
                .status(MsgStatus.STREAMING)
                .build());

        return ChatTurnResponse.builder()
                .firstTurn(previous.isEmpty())
                .context(context)
                .userMessage(toResponse(userMessage, null))
                .assistantMessage(toResponse(assistantMessage, null))
                .build();
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

        if (message.getStatus() == MsgStatus.COMPLETED) {
            Conversation conversation = message.getConversation();
            usageLimitService.consumeAnswer(conversation.getUser(), conversation.getGuestSession(), message.getContent());
        }

        return toResponse(message, ticketIdOf(message));
    }

    @Override
    @Transactional
    public ClarificationStatusResponse cancelClarification(UUID id, CancelClarificationRequest request) {
        // Unknown id, another conversation, a USER message and a message without a panel all look
        // the same to the caller (SPEC-clarification-panel §7.2).
        Message message = messageRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));
        if (!message.getConversation().getId().equals(request.conversationId())
                || message.getRole() != MsgRole.ASSISTANT
                || message.getMetadata() == null
                || !(message.getMetadata().get(CLARIFICATION_KEY) instanceof Map<?, ?> clarification)) {
            throw new AppException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        Object current = clarification.get("status");
        if (!CLARIFICATION_CANCELLED.equals(request.status())) {
            throw new AppException(ErrorCode.CLARIFICATION_INVALID_STATUS_TRANSITION);
        }
        if (CLARIFICATION_OPEN.equals(current)) {
            // Copy both levels: Hibernate only notices a changed jsonb value through a new reference,
            // and every other key (panel, schema_version, calculation, ...) must survive untouched.
            Map<String, Object> updatedClarification = new LinkedHashMap<>();
            clarification.forEach((key, value) -> updatedClarification.put(String.valueOf(key), value));
            updatedClarification.put("status", CLARIFICATION_CANCELLED);
            Map<String, Object> updatedMetadata = new LinkedHashMap<>(message.getMetadata());
            updatedMetadata.put(CLARIFICATION_KEY, updatedClarification);
            message.setMetadata(updatedMetadata);
            messageRepository.save(message);
        } else if (!CLARIFICATION_CANCELLED.equals(current)) {
            throw new AppException(ErrorCode.CLARIFICATION_INVALID_STATUS_TRANSITION);
        }

        return ClarificationStatusResponse.builder()
                .messageId(message.getId())
                .conversationId(message.getConversation().getId())
                .status(CLARIFICATION_CANCELLED)
                .build();
    }

    @Override
    public List<MessageResponse> getByConversation(UUID conversationId, Integer limit, boolean forContext) {
        return toResponses(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId), limit, forContext);
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

    /**
     * Only {@code clarification_answers} is accepted, and only up to 32 KB serialized. The content
     * itself is not inspected: unisage-agent already validated it against the stored panel, and the
     * worst a client could do by calling this route itself is fake a card on its own turn.
     */
    private static Map<String, Object> validateTurnMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        if (!ALLOWED_TURN_METADATA_KEYS.containsAll(metadata.keySet())) {
            throw new AppException(ErrorCode.MESSAGE_METADATA_INVALID);
        }
        try {
            if (SIZE_MAPPER.writeValueAsBytes(metadata).length > MAX_TURN_METADATA_BYTES) {
                throw new AppException(ErrorCode.MESSAGE_METADATA_INVALID);
            }
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.MESSAGE_METADATA_INVALID);
        }
        return metadata;
    }

    /** Messages are ordered by created_at only - spin until the clock is past the previous row's stamp. */
    private static void waitPastCreatedAt(Message message) {
        LocalDateTime createdAt = message.getCreatedAt();
        if (createdAt == null) {
            return;
        }
        LocalDateTime threshold = createdAt.plusNanos(CREATED_AT_GAP_NANOS);
        while (!LocalDateTime.now().isAfter(threshold)) {
            Thread.onSpinWait();
        }
    }

    private List<MessageResponse> toResponses(List<Message> messages, Integer limit, boolean forContext) {
        int effectiveLimit = (limit != null && limit > 0) ? limit : Integer.MAX_VALUE;
        if (forContext) {
            // Only the prompt context is capped - capping the UI too hid older messages whenever a
            // conversation was reopened (UNISAGE-94).
            int maxMessageHistory = configResolver.getInt("chat.max_history_messages", 20);
            effectiveLimit = Math.min(effectiveLimit, maxMessageHistory);
        }
        if (messages.size() > effectiveLimit) {
            messages = messages.subList(messages.size() - effectiveLimit, messages.size());
        }

        // One query for the whole page instead of one per message. Only regular Reports: a message
        // may also carry one calculation-item ticket per item, which must not collide in this map.
        List<UUID> messageIds = messages.stream().map(Message::getId).toList();
        Map<UUID, UUID> ticketIdByMessage = messages.isEmpty() ? Map.of()
                : ticketRepository.findByMessageIdInAndCalculationItemIdIsNull(messageIds).stream()
                        .collect(Collectors.toMap(t -> t.getMessage().getId(), Ticket::getId));

        return messages.stream()
                .map(message -> toResponse(message, ticketIdByMessage.get(message.getId())))
                .collect(Collectors.toList());
    }

    /** Only the regular Report - calculation-item tickets surface through metadata.calculation_feedback. */
    private UUID ticketIdOf(Message message) {
        return ticketRepository.findByMessageIdAndCalculationItemIdIsNull(message.getId()).map(Ticket::getId).orElse(null);
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
