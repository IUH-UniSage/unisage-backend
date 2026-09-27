package com.unisage.backend.event;

/**
 * Fired after a transaction that created/replaced a {@code QUEUED} verification job commits
 * (plan.md "Verification lifecycle" step 1: "publish verification-requested sau commit (chỉ để
 * đánh thức, không bắt buộc)"). Purely a wake-up signal for the Python verifier's Celery Beat loop
 * — Beat also polls every 15s on its own, so a missed publish is never a correctness issue.
 */
public record VerificationRequestedEvent(java.util.UUID jobId) {
}
