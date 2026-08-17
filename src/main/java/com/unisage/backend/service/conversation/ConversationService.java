package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ConversationResponse;

public interface ConversationService {

    /** {@code userId} is null for guest chat — see the conversation-ownership ADR. */
    ConversationResponse create(CreateConversationRequest request, UUID userId, String ipAddress);

    List<ConversationResponse> getByUser(UUID userId);

    void softDelete(UUID id);

    /** Attaches a previously-guest (user == null) conversation to a now-logged-in user. */
    ConversationResponse claim(UUID conversationId, UUID userId);
}
