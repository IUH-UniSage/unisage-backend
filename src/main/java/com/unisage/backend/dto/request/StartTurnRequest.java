package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record StartTurnRequest(
    @NotNull(message = "conversationId không được để trống")
    UUID conversationId,

    @NotBlank(message = "content không được để trống")
    String content
) {}
