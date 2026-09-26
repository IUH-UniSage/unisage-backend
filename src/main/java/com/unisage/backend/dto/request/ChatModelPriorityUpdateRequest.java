package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotNull;

/** {@code PATCH /chat-models/{id}/priority} body — applies immediately, no verify needed (plan.md "Credential rotation"). */
public record ChatModelPriorityUpdateRequest(
    @NotNull(message = "priority không được để trống")
    Integer priority
) {}
