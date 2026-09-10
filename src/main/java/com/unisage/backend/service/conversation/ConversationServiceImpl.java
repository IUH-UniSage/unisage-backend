package com.unisage.backend.service.conversation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ConversationResponse;
import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.UserRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ConversationResponse create(CreateConversationRequest request, UUID userId, GuestSession guestSession) {
        User user = null;
        if (userId != null) {
            user = userRepository.findById(userId)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        }

        Conversation conversation = Conversation.builder()
                .user(user)
                .guestSession(user == null ? guestSession : null)
                .title(request.title())
                .build();

        conversation = conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    @Override
    public List<ConversationResponse> getByUser(UUID userId) {
        return conversationRepository.findActiveByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ConversationResponse> getByGuestSession(GuestSession guestSession) {
        if (guestSession == null) {
            return List.of();
        }
        return conversationRepository.findActiveUnclaimedByGuestSessionId(guestSession.getId()).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void softDelete(UUID id) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
        conversation.setDeletedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    @Override
    @Transactional
    public ConversationResponse claim(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        if (conversation.getUser() != null) {
            throw new AppException(ErrorCode.CONVERSATION_ALREADY_CLAIMED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        conversation.setUser(user);
        conversation.setGuestSession(null);
        conversation = conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return ConversationResponse.builder()
                .id(conversation.getId())
                .userId(conversation.getUser() != null ? conversation.getUser().getId() : null)
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .build();
    }
}
