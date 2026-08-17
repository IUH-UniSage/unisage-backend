package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record CreateConversationRequest(
    String title
) {}
