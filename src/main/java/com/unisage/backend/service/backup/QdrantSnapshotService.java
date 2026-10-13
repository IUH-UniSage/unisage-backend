package com.unisage.backend.service.backup;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;

import com.unisage.backend.entity.BackupRun;
import com.unisage.backend.entity.enums.BackupStatus;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.integration.QdrantSnapshotClient;
import com.unisage.backend.integration.QdrantSnapshotClient.Snapshot;
import com.unisage.backend.repository.BackupRunRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Snapshots one Qdrant collection into object storage (ADR-0009): create on the node, stream it
 * into the backup bucket while checking Qdrant's SHA-256, then delete it on the node so snapshots
 * never fill up the Qdrant disk. Only snapshots made here are deleted; one made by hand stays.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantSnapshotService {

    static final String PREFIX = "qdrant/";

    private final QdrantSnapshotClient qdrantClient;
    private final BackupStorage storage;
    private final BackupRunRepository backupRunRepository;
    private final BackupRunRecorder recorder;

    public List<String> collections() {
        return qdrantClient.listCollections();
    }

    public BackupArtifact snapshot(String collection) {
        Snapshot snapshot = qdrantClient.createSnapshot(collection);
        String key = PREFIX + collection + "/" + snapshot.name();
        try {
            String sha256 = qdrantClient.download(collection, snapshot.name(), body -> {
                MessageDigest digest = sha256();
                storage.upload(new DigestInputStream(body, digest), snapshot.size(), key);
                return HexFormat.of().formatHex(digest.digest());
            });
            if (snapshot.checksum() != null && !snapshot.checksum().equalsIgnoreCase(sha256)) {
                storage.deletePrefix(key);
                throw new BackupException("checksum mismatch for " + key + ": Qdrant says "
                        + snapshot.checksum() + ", uploaded " + sha256);
            }
            return new BackupArtifact(key, 1, snapshot.size(), sha256);
        } finally {
            try {
                qdrantClient.deleteSnapshot(collection, snapshot.name());
            } catch (BackupException e) {
                // The copy in storage is what matters; a leftover only uses Qdrant disk.
                log.warn("Could not delete snapshot {} from Qdrant: {}", snapshot.name(), e.getMessage());
            }
        }
    }

    /** Keeps the newest {@code keep} successful snapshots of {@code collection}. */
    public void applyRetention(String collection, int keep) {
        List<BackupRun> snapshots = backupRunRepository
                .findByBackupTypeAndTargetAndStatusAndPurgedAtIsNullOrderByStartedAtDesc(
                        BackupType.QDRANT_SNAPSHOT, collection, BackupStatus.SUCCEEDED);
        for (BackupRun old : snapshots.subList(Math.min(keep, snapshots.size()), snapshots.size())) {
            storage.deletePrefix(old.getStoragePrefix());
            recorder.markPurged(old);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
