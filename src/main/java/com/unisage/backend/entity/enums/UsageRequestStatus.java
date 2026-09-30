package com.unisage.backend.entity.enums;

/**
 * Outcome of a {@code RequestUsageLog} (parent, whole request) or a {@code RequestUsageLine}
 * (child, one provider call). {@code PARTIAL} only applies to the parent - it has at least one
 * {@code ERROR} line but still produced an answer (e.g. a failed-over attempt before a successful
 * one); a line itself is always exactly {@code SUCCESS} or {@code ERROR}, never {@code PARTIAL}.
 */
public enum UsageRequestStatus {
    SUCCESS,
    ERROR,
    PARTIAL
}
