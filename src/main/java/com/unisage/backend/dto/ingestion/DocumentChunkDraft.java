package com.unisage.backend.dto.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentChunkDraft(
        @Min(0) int index,
        @NotBlank String content,
        @Min(0) int tokenCount,
        @NotNull Map<String, Object> metadata
) {
}
