package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record AccessLevelRequest(
    @NotNull Integer level,
    String description
) {}
