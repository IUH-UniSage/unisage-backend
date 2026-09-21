package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Builder;

/**
 * A single archived version of a document's file, returned by GET /documents/{id}/versions.
 */
@Builder
public record DocumentVersionResponse(
    UUID id,
    Integer versionNumber,
    String fileType,
    String fileName,
    String fileUrl,
    UUID uploadedByUserId,
    String uploadedByName,
    LocalDateTime createdAt
) {}
