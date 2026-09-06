package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.DocStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record UpdateDocumentStatusRequest(
    @NotNull DocStatus status
) {}
