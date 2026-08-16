package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record DepartmentRequest(
    @NotBlank String name,
    String description,
    UUID parentId
) {}
