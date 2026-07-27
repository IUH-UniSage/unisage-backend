package com.unisage.backend.dto.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentIngestionConfirmRequest(
        @NotNull UUID documentId,
        @NotBlank String source,
        @NotNull Map<String, Object> metadata,
        @NotEmpty List<@Valid DocumentChunkDraft> chunks
) {
}
