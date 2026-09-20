package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record UpdateSystemConfigRequest(
    @NotBlank String value
) {}
