package com.unisage.backend.service.backup;

/** What one backup run left in object storage. */
public record BackupArtifact(String storagePrefix, int fileCount, long sizeBytes, String checksum) {
}
