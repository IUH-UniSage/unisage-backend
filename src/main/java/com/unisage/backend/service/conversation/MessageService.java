package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;

public interface MessageService {

    /** {@code guestSessionToken} is the raw cookie/header value; null for authenticated callers. */
    MessageResponse send(SendMessageRequest request, UUID callerId, String guestSessionToken);

    MessageResponse update(UUID id, UpdateMessageRequest request);

    List<MessageResponse> getByConversation(UUID conversationId, Integer limit);

    MessageResponse getById(UUID id);
}
