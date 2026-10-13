package com.unisage.backend.entity.enums;

public enum BackupType {
    /** pg_basebackup of the whole cluster. */
    FULL,
    /** PG17 incremental pg_basebackup, always against the latest Full's manifest. */
    DIFFERENTIAL,
    /** Archived WAL segments (the transaction log) shipped to object storage. */
    WAL,
    /** One Qdrant collection snapshot. */
    QDRANT_SNAPSHOT
}
