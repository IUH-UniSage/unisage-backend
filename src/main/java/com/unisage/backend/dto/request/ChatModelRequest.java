package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record ChatModelRequest(
    @NotNull(message = "sourceType không được để trống")
    ChatModelSourceType sourceType,

    String llmProvider,

    @NotBlank(message = "llmModelName không được để trống")
    String llmModelName,

    String modelSourceRef,

    String apiKey,

    @NotBlank(message = "apiBaseUrl không được để trống")
    String apiBaseUrl,

    @NotNull(message = "maxRpm không được để trống")
    Integer maxRpm,

    Integer priority
) {}
