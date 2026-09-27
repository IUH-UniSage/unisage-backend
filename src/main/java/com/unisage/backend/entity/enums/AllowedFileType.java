package com.unisage.backend.entity.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;

/**
 * Whitelist of file types accepted for document upload (enforced in FileServiceImpl.upload()).
 * Not to be confused with Document.fileType — that is a free-text field the client supplies
 * as display metadata and does not gate what actually gets stored in MinIO.
 */
@Getter
public enum AllowedFileType {
    TXT(".txt"),
    PDF(".pdf"),
    DOCX(".docx"),
    // No legacy .doc: unisage-agent's parser only reads OOXML .docx, so a .doc upload was stored
    // and then failed at ingestion (UNISAGE-94).
    HTML(".html");

    private final String extension;

    AllowedFileType(String extension) {
        this.extension = extension;
    }

    /**
     * Resolves the whitelist entry matching the filename's extension (case-insensitive).
     * Empty when filename is null/blank or its extension isn't in the whitelist.
     */
    public static Optional<AllowedFileType> fromExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase();
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == lower.length() - 1) {
            return Optional.empty();
        }
        String extension = lower.substring(dotIndex);
        return Arrays.stream(values())
                .filter(type -> type.extension.equals(extension))
                .findFirst();
    }
}
