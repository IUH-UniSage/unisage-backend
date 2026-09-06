package com.unisage.backend.dto.request;

import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record SendMessageRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotNull(message = "role không được để trống")
    MsgRole role,

    // Blank is allowed only for an ASSISTANT STREAMING placeholder (see
    // MessageServiceImpl#send) - validated there, not here, since the rule
    // depends on `status`.
    String content,

    // Optional; defaults to COMPLETED. The only other value a caller may
    // request is STREAMING, for an ASSISTANT placeholder that a later
    // PATCH /messages/{id} finalizes to COMPLETED/ERROR.
    MsgStatus status,

    UUID chatModelId,
    Object citations,
    Float retrievalScore,
    Map<String, Object> metadata
) {}
