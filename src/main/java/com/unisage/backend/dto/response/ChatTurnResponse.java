package com.unisage.backend.dto.response;

import java.util.List;

import lombok.Builder;

@Builder
public record ChatTurnResponse(
    boolean firstTurn,
    List<MessageResponse> context,
    MessageResponse userMessage,
    MessageResponse assistantMessage
) {}
