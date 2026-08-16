package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ConversationResponse;

public interface ConversationService {

    ConversationResponse create(CreateConversationRequest request, UUID userId);

    List<ConversationResponse> getByUser(UUID userId);

    void softDelete(UUID id);
}
