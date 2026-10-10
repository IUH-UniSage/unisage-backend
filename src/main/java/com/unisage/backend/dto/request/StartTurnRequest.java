package com.unisage.backend.dto.request;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record StartTurnRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotBlank(message = "content không được để trống")
    String content,

    // Optional metadata for the USER message. Only `clarification_answers` is accepted (the card
    // the web renders for a clarification submit) - checked in MessageServiceImpl#startTurn.
    Map<String, Object> metadata
) {
    public StartTurnRequest(UUID conversationId, String content) {
        this(conversationId, content, null);
    }
}
