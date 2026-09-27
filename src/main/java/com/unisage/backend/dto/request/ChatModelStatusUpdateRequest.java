package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.ChatModelStatus;
import jakarta.validation.constraints.NotNull;

/** {@code PATCH /chat-models/{id}/status} body — only ACTIVE/INACTIVE are SA-triggerable (plan.md "State machine"). */
public record ChatModelStatusUpdateRequest(
    @NotNull(message = "status không được để trống")
    ChatModelStatus status
) {}
