package com.unisage.backend.dto.request;

import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record SendMessageRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotNull(message = "role không được để trống")
    MsgRole role,

    @NotBlank(message = "content không được để trống")
    String content,

    UUID chatModelId,
    Object citations,
    Float retrievalScore,
    Map<String, Object> metadata
) {}
