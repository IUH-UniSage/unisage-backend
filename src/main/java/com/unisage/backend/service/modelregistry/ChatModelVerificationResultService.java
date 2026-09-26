package com.unisage.backend.service.modelregistry;

import java.util.UUID;

import com.unisage.backend.dto.request.internal.InternalVerificationResultRequest;
import com.unisage.backend.dto.response.internal.InternalVerificationResultResponse;

/**
 * {@code POST /internal/model-registry/verifications/{jobId}/result} — plan.md "Internal API
 * contract" endpoint #5 / "Verification lifecycle" steps 3-4. Runs entirely in one transaction,
 * locking the {@code ChatModel} row before the job row (same fixed order as staged rotation, to
 * avoid deadlock), and never writes anything unless the job is still {@code RUNNING} with a
 * matching, unexpired lease token (checked with the database's own clock).
 */
public interface ChatModelVerificationResultService {

    InternalVerificationResultResponse submitResult(UUID jobId, InternalVerificationResultRequest request);
}
