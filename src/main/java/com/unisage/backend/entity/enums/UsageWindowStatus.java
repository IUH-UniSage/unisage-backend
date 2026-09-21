package com.unisage.backend.entity.enums;

/** What a usage window looks like to the caller, so the client never has to infer it from nulls. */
public enum UsageWindowStatus {
    /** The plan (or the feature switch) sets no limit for this window. */
    UNLIMITED,
    /** No running window: nothing used yet, or the last window has expired. */
    IDLE,
    /** A window is running and counting. */
    ACTIVE
}
