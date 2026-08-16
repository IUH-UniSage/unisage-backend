package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.response.MessageResponse;

public interface MessageService {

    MessageResponse send(SendMessageRequest request);

    List<MessageResponse> getByConversation(UUID conversationId);

    MessageResponse getById(UUID id);
}
