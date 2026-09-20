package com.unisage.backend.dto.response;

import java.util.UUID;

import lombok.Builder;

/**
 * Minimal document view for opening a chat citation. Deliberately excludes {@code sourceUrl} (the
 * MinIO object key) and every admin field — only what the citation drawer needs to preview/download.
 */
@Builder
public record CitationDocumentResponse(
    UUID id,
    String title,
    String fileType,
    String fileName,
    String fileUrl
) {}
