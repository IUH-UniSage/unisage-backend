package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/** {@code PATCH /conversations/{id}/pin}: true pins the conversation in the sidebar, false unpins it. */
@Builder
public record PinConversationRequest(
    @NotNull(message = "Trạng thái ghim không được để trống")
    Boolean pinned
) {}
