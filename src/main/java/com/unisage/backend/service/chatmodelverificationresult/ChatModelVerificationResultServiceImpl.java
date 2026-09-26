package com.unisage.backend.service.chatmodelverificationresult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.InternalVerificationResultRequest;
import com.unisage.backend.dto.response.internal.InternalVerificationResultResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.entity.enums.VerificationResultType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.service.chatmodelcandidatepromotion.ChatModelCandidatePromotionService;
import com.unisage.backend.service.chatmodelcandidatepromotion.ChatModelCandidatePromotionService.Outcome;
import com.unisage.backend.utils.SecretRedactor;

import lombok.RequiredArgsConstructor;

/**
 * See {@link ChatModelVerificationResultService}. Lock ordering (model, then job) and the
 * DB-clock lease check are exactly plan.md "Verification lifecycle" step 4 — this class only does
 * the pre-check and the TRANSIENT/PERMANENT branches; the OK branch (CAS promote, embedding
 * identity guard, version bump, event) is delegated to {@link ChatModelCandidatePromotionService}
 * so that logic exists in exactly one place.
 */
@Service
@RequiredArgsConstructor
public class ChatModelVerificationResultServiceImpl implements ChatModelVerificationResultService {

    /** plan.md "Verification lifecycle": "backoff 30s/2m/10m" indexed by the job's attempt number. */
    private static final Duration[] TRANSIENT_BACKOFF = {
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10)
    };

    private final ChatModelRepository chatModelRepository;
    private final ChatModelVerificationRepository chatModelVerificationRepository;
    private final ChatModelCandidatePromotionService promotionService;

    @Override
    @Transactional
    public InternalVerificationResultResponse submitResult(UUID jobId, InternalVerificationResultRequest request) {
        // Scalar-only lookup to discover which ChatModel row to lock first (plan.md step 4.1:
        // "Đọc chat_model_id của job (không khoá)") — deliberately NOT an entity read: loading a
        // ChatModelVerification here would leave a stale managed instance in the persistence
        // context that a later locked re-read would not refresh (Hibernate returns the cached
        // instance for an already-loaded id instead of re-hydrating it from the new locked SELECT).
        UUID chatModelId = chatModelVerificationRepository.findChatModelIdById(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_JOB_NOT_FOUND));

        // Fixed lock order — model, THEN job — matching ChatModelServiceImpl#update/#verify
        // (staged rotation) so the two paths can never deadlock on each other.
        ChatModel model = chatModelRepository.findByIdForUpdate(chatModelId)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        ChatModelVerification job = chatModelVerificationRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_JOB_NOT_FOUND));

        boolean leaseValid = job.getStatus() == ChatModelVerificationStatus.RUNNING
                && request.leaseToken().equals(job.getLeaseToken())
                && chatModelVerificationRepository.isLeaseCurrentlyValid(jobId, request.leaseToken());

        if (!leaseValid) {
            // Nothing is written in this branch — no counter, no version bump, no event (plan.md
            // step 4.3). A SUPERSEDED/CANCELLED job, or QUEUED (never claimed by this token), or an
            // expired lease all land here regardless of whether the token itself matches.
            if (request.leaseToken().equals(job.getLastResultLeaseToken())) {
                return new InternalVerificationResultResponse(false, true);
            }
            throw new AppException(ErrorCode.VERIFICATION_LEASE_LOST);
        }

        // Idempotency marker written immediately, before branching (plan.md step 4.4) — a retried
        // submission of this same token short-circuits to `duplicate` above on its next attempt,
        // even if this transaction later fails after this point (rolled back together with it, so
        // "written" here only becomes visible once the whole transaction — including the promote/
        // backoff/fail branch below — commits).
        job.setLastResultLeaseToken(request.leaseToken());

        return switch (request.resultType()) {
            case OK -> applyOk(job, request);
            case TRANSIENT -> applyTransient(job, request);
            case PERMANENT -> applyTerminal(job, request);
        };
    }

    private InternalVerificationResultResponse applyOk(ChatModelVerification job, InternalVerificationResultRequest request) {
        // EMBEDDING-only measurements — harmless to set for CHAT/EXTRACTION, promote() only reads
        // them for an EMBEDDING row (plan.md "Embedding identity guard").
        job.setEmbeddingDimension(request.embeddingDimension());
        job.setEmbeddingFingerprint(request.embeddingFingerprint());

        Outcome outcome = promotionService.promote(job);
        // PROMOTED and REINDEX_REQUIRED both mean "your result was recorded and processed"; only
        // SUPERSEDED (the CAS lost the race to a newer edit) means nothing from this result stuck.
        boolean applied = outcome == Outcome.PROMOTED || outcome == Outcome.REINDEX_REQUIRED;
        return new InternalVerificationResultResponse(applied, false);
    }

    private InternalVerificationResultResponse applyTransient(ChatModelVerification job, InternalVerificationResultRequest request) {
        if (job.getAttempt() < job.getMaxAttempts()) {
            job.setStatus(ChatModelVerificationStatus.QUEUED);
            job.setLeaseToken(null);
            job.setLeaseUntil(null);
            job.setNextAttemptAt(LocalDateTime.now().plus(backoffFor(job.getAttempt())));
            setErrorFields(job, request);
            chatModelVerificationRepository.save(job);
            return new InternalVerificationResultResponse(true, false);
        }
        // Attempts exhausted — TRANSIENT with nothing left to retry behaves like PERMANENT.
        return applyTerminal(job, request);
    }

    private InternalVerificationResultResponse applyTerminal(ChatModelVerification job, InternalVerificationResultRequest request) {
        job.setStatus(ChatModelVerificationStatus.FAILED);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        job.setFinishedAt(LocalDateTime.now());
        setErrorFields(job, request);
        chatModelVerificationRepository.save(job);
        return new InternalVerificationResultResponse(true, false);
    }

    private void setErrorFields(ChatModelVerification job, InternalVerificationResultRequest request) {
        job.setErrorType(request.resultType().name());
        job.setErrorCode(request.errorCode());
        // Redact defensively even though unisage-agent's safe_error_message already redacts its
        // side (plan.md Task 6 acceptance: "Java redact lại error_message trước khi lưu").
        job.setErrorMessage(request.message() != null ? SecretRedactor.redact(request.message()) : null);
    }

    private Duration backoffFor(int attempt) {
        int index = Math.max(0, Math.min(attempt - 1, TRANSIENT_BACKOFF.length - 1));
        return TRANSIENT_BACKOFF[index];
    }
}
