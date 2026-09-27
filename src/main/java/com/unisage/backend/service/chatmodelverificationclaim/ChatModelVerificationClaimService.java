package com.unisage.backend.service.chatmodelverificationclaim;

import java.util.List;

import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse;

/**
 * {@code POST /internal/model-registry/verifications/claim} — plan.md "Internal API contract"
 * endpoint #4 / "Verification lifecycle" step 2. Claims up to {@code limit} jobs that are ready
 * (QUEUED past {@code next_attempt_at}, or RUNNING with an expired lease) using the
 * {@code FOR UPDATE SKIP LOCKED} selection already built for Task 1
 * ({@link com.unisage.backend.repository.ChatModelVerificationRepository#findClaimableIds}), and
 * hands each one a fresh fencing token.
 */
public interface ChatModelVerificationClaimService {

    List<InternalVerificationClaimResponse> claim(int limit);
}
