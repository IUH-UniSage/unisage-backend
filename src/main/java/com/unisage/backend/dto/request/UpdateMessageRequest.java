package com.unisage.backend.dto.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UpdateMessageRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    // Blank is allowed only for status=ERROR (the graph can fail before
    // streaming any token) - validated in MessageServiceImpl#update, not
    // here, since the rule depends on `status`.
    String content,

    @NotNull(message = "status không được để trống")
    MsgStatus status,

    List<Map<String, Object>> citations,
    Float retrievalScore,
    Map<String, Object> metadata
) {}
