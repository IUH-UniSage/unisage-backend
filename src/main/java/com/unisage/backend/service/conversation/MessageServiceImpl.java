package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
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

    @Override
    @Transactional
    public MessageResponse send(SendMessageRequest request) {
        Conversation conversation = conversationRepository.findById(request.conversationId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        ChatModel chatModel = null;
        if (request.chatModelId() != null) {
            chatModel = chatModelRepository.findById(request.chatModelId())
                    .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        }

        UsageLimit usageLimit = null;
        if (request.role() == MsgRole.USER) {
            usageLimit = usageLimitService.checkAndGetOrCreate(conversation.getUser(), conversation.getIpAddress());
        }

        Message message = Message.builder()
                .conversation(conversation)
                .role(request.role())
                .content(request.content())
                .status(MsgStatus.COMPLETED)
                .chatModel(chatModel)
                .citations(request.citations())
                .retrievalScore(request.retrievalScore())
                .metadata(request.metadata())
                .build();
        message = messageRepository.save(message);

        usageLimitService.increment(usageLimit);

        return toResponse(message);
    }

    @Override
    public List<MessageResponse> getByConversation(UUID conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public MessageResponse getById(UUID id) {
        Message message = messageRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));
        return toResponse(message);
    }

    private MessageResponse toResponse(Message message) {
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
                .build();
    }
}
