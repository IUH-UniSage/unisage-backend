package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;

import lombok.Builder;

@Builder
public record MessageResponse(
    UUID id,
    UUID conversationId,
    MsgRole role,
    String content,
    MsgStatus status,
    UUID chatModelId,
    Object citations,
    Float retrievalScore,
    Map<String, Object> metadata,
    LocalDateTime createdAt
) {}
