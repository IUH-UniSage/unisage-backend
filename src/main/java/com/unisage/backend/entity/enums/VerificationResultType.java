package com.unisage.backend.entity.enums;

/**
 * Outcome a verifier reports for a claimed job — {@code POST /internal/model-registry/verifications/{jobId}/result}
 * (plan.md "Verification lifecycle", step 4). Distinct from {@link ChatModelVerificationStatus}: this is what the
 * verifier observed when it tried the candidate credential, not the job's own lifecycle state — Java maps this
 * (plus attempt/max_attempts) onto the job's next status.
 */
public enum VerificationResultType {
    /** Candidate credential worked — Java attempts to promote it onto the row (CAS against generation/revision). */
    OK,
    /** Rate limit, timeout, transient provider 5xx — retried later if attempts remain. */
    TRANSIENT,
    /** Invalid key, unsupported model, or any error that a retry cannot fix — job ends FAILED immediately. */
    PERMANENT
}
