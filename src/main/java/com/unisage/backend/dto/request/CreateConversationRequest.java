package com.unisage.backend.dto.request;

import java.util.UUID;

import lombok.Builder;

@Builder
public record CreateConversationRequest(
    String title,
    UUID departmentId
) {}
