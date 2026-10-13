package com.unisage.backend.entity.enums;

public enum RestoreType {
    /** The whole Postgres cluster, from a Full (plus a Differential) and optionally WAL. */
    POSTGRES,
    /** One Qdrant collection, from one of its snapshots. */
    QDRANT
}
