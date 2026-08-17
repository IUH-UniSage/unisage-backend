package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record AiChatRequest(
    String message,
    String userId
) {}
