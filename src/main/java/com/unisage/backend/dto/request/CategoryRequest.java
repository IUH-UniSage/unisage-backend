package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record CategoryRequest(
    @NotBlank String name,
    @NotBlank String description,
    String status
) {}
