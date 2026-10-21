package com.unisage.backend.dto.response;

import java.time.LocalDateTime;

/**
 * One object a backup run stored in MinIO. {@code downloadUrl} is a presigned link that saves the
 * file as {@code downloadName}; {@code lastModified} is UTC like the other backup timestamps.
 */
public record BackupFileResponse(
        String name,
        String key,
        long sizeBytes,
        LocalDateTime lastModified,
        String downloadName,
        String downloadUrl) {
}
