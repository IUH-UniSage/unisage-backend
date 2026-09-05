package com.unisage.backend.dto.request;

import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UpdateMessageRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotBlank(message = "content không được để trống")
    String content,

    @NotNull(message = "status không được để trống")
    MsgStatus status,

    Object citations,
    Float retrievalScore,
    Map<String, Object> metadata
) {}
