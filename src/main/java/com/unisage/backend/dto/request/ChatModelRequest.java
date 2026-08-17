package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record ChatModelRequest(
    @NotBlank(message = "llmProvider không được để trống")
    String llmProvider,

    @NotBlank(message = "llmModelName không được để trống")
    String llmModelName,

    @NotBlank(message = "apiKey không được để trống")
    String apiKey,

    @NotBlank(message = "apiBaseUrl không được để trống")
    String apiBaseUrl,

    @NotNull(message = "maxRpm không được để trống")
    Integer maxRpm,

    Integer priority
) {}
