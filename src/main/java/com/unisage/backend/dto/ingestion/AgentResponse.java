package com.unisage.backend.dto.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentResponse<T>(
        String status,
        T data,
        String message
) {
}
