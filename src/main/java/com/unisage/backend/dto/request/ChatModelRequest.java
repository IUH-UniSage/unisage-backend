package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record ChatModelRequest(
    /** Required at creation; immutable afterwards (plan.md "Credential rotation") — ignored on update. */
    @NotNull(message = "modelPurpose không được để trống")
    ChatModelPurpose modelPurpose,

    @NotNull(message = "sourceType không được để trống")
    ChatModelSourceType sourceType,

    String llmProvider,

    @NotBlank(message = "llmModelName không được để trống")
    String llmModelName,

    /** Optional operator-chosen label, purely a display aid - never affects routing/verification. */
    String displayName,

    String modelSourceRef,

    String apiKey,

    @NotBlank(message = "apiBaseUrl không được để trống")
    String apiBaseUrl,

    @NotNull(message = "maxRpm không được để trống")
    Integer maxRpm,

    Integer priority
) {}
