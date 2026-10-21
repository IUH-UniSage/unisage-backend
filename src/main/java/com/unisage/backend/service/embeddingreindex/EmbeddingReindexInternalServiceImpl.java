package com.unisage.backend.service.embeddingreindex;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexProgressRequest;
import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexResultRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexClaimResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexStatusResponse;
import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse.CandidateCredential;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.EmbeddingReindexRun;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.EmbeddingReindexStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.EmbeddingReindexRunRepository;
import com.unisage.backend.service.embeddingindexidentity.EmbeddingIndexIdentityService;
import com.unisage.backend.service.embeddingindexpointer.EmbeddingIndexPointerService;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.utils.EmbeddingIdentityMismatches;
import com.unisage.backend.utils.SecretRedactor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lease protocol mirrors {@code ChatModelVerificationClaimServiceImpl}: claim locks rows
 * {@code FOR UPDATE SKIP LOCKED} and issues a fresh lease token; progress renews it; a token that no
 * longer matches is 409 {@code EMBEDDING_REINDEX_LEASE_LOST}. A CANCELLED run keeps its token so the
 * agent is told {@code CANCELLED} (and cleans up) instead of getting a 409.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingReindexInternalServiceImpl implements EmbeddingReindexInternalService {

    static final long LEASE_SECONDS = 600;

    private final EmbeddingReindexRunRepository runRepository;
    private final ChatModelRepository chatModelRepository;
    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private final EmbeddingIndexPointerService pointerService;
    private final ModelRegistryVersionService modelRegistryVersionService;

    @Override
    @Transactional
    public List<InternalEmbeddingReindexClaimResponse> claim(int limit) {
        List<InternalEmbeddingReindexClaimResponse> claimed = new ArrayList<>();
        for (UUID id : runRepository.findClaimableIds(limit)) {
            EmbeddingReindexRun run = runRepository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Claimable re-index run vanished mid-transaction: " + id));
            ChatModel model = run.getTargetChatModel();
            String invalid = targetModelProblem(run, model);
            if (invalid != null) {
                finish(run, EmbeddingReindexStatus.FAILED, invalid);
                continue;
            }

            LocalDateTime now = LocalDateTime.now();
            run.setStatus(EmbeddingReindexStatus.RUNNING);
            run.setLeaseToken(UUID.randomUUID());
            run.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
            if (run.getStartedAt() == null) {
                run.setStartedAt(now);
            }
            runRepository.save(run);
            claimed.add(toClaimResponse(run, model));
        }
        return claimed;
    }

    @Override
    @Transactional
    public InternalEmbeddingReindexStatusResponse progress(UUID runId, InternalEmbeddingReindexProgressRequest request) {
        EmbeddingReindexRun run = lockOwnedRun(runId, request.leaseToken());
        if (run.getStatus() == EmbeddingReindexStatus.CANCELLED) {
            return new InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus.CANCELLED);
        }
        run.setTotalPoints(request.totalPoints());
        run.setProcessedPoints(request.processedPoints());
        run.setFailedPoints(request.failedPoints());
        run.setLeaseUntil(LocalDateTime.now().plusSeconds(LEASE_SECONDS));
        runRepository.save(run);
        return new InternalEmbeddingReindexStatusResponse(run.getStatus());
    }

    @Override
    @Transactional
    public InternalEmbeddingReindexStatusResponse result(UUID runId, InternalEmbeddingReindexResultRequest request) {
        EmbeddingReindexRun run = lockOwnedRun(runId, request.leaseToken());
        if (run.getStatus() == EmbeddingReindexStatus.CANCELLED) {
            return new InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus.CANCELLED);
        }
        run.setTotalPoints(request.totalPoints());
        run.setProcessedPoints(request.processedPoints());
        run.setFailedPoints(request.failedPoints());

        if (!Boolean.TRUE.equals(request.succeeded())) {
            String reason = request.errorMessage() != null
                    ? SecretRedactor.redact(request.errorMessage())
                    : "Agent báo re-index thất bại.";
            finish(run, EmbeddingReindexStatus.FAILED, reason);
            modelRegistryVersionService.bump();
            return new InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus.FAILED);
        }

        // Lock the model before checking it so nobody edits/activates it between check and cutover.
        ChatModel model = chatModelRepository.findByIdForUpdate(run.getTargetChatModel().getId())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        String problem = targetModelProblem(run, model);
        if (problem == null) {
            problem = targetIdentityProblem(run, model);
        }
        if (problem != null) {
            finish(run, EmbeddingReindexStatus.FAILED, problem);
            modelRegistryVersionService.bump();
            return new InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus.FAILED);
        }

        pointerService.pointTo(run.getTargetCollection());
        chatModelRepository.deactivateOtherActiveEmbedding(model.getId());
        if (chatModelRepository.updateStatusIfCurrent(model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE) == 0) {
            // Unreachable while the row lock is held; rolls the pointer move back with it.
            throw new AppException(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
        }
        finish(run, EmbeddingReindexStatus.SUCCEEDED, null);
        modelRegistryVersionService.bump();
        log.info("Embedding re-index {} cut over: {} -> {} (model {})",
                run.getId(), run.getSourceCollection(), run.getTargetCollection(), model.getId());
        return new InternalEmbeddingReindexStatusResponse(EmbeddingReindexStatus.SUCCEEDED);
    }

    /** The run, locked, if {@code leaseToken} still owns it (RUNNING, or CANCELLED under that token). */
    private EmbeddingReindexRun lockOwnedRun(UUID runId, UUID leaseToken) {
        EmbeddingReindexRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new AppException(ErrorCode.EMBEDDING_REINDEX_RUN_NOT_FOUND));
        boolean owned = Objects.equals(run.getLeaseToken(), leaseToken)
                && (run.getStatus() == EmbeddingReindexStatus.RUNNING
                    || run.getStatus() == EmbeddingReindexStatus.CANCELLED);
        if (!owned) {
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_LEASE_LOST);
        }
        return run;
    }

    /** Why the target model can no longer take over, or null when it still can. */
    private static String targetModelProblem(EmbeddingReindexRun run, ChatModel model) {
        if (!EmbeddingReindexServiceImpl.isReindexTarget(model)) {
            return "Model đích không còn ở trạng thái đã xác minh và đang tắt.";
        }
        if (!Objects.equals(model.getRevision(), run.getTargetModelRevision())) {
            return "Model đích đã bị sửa trong lúc re-index.";
        }
        return null;
    }

    private String targetIdentityProblem(EmbeddingReindexRun run, ChatModel model) {
        Optional<InternalEmbeddingIndexIdentityResponse> identity =
                embeddingIndexIdentityService.getIdentity(run.getTargetCollection());
        if (identity.isEmpty()) {
            return "Collection đích chưa có danh tính embedding.";
        }
        List<String> mismatches = EmbeddingIdentityMismatches.of(model, identity.get());
        if (!mismatches.isEmpty()) {
            return "Danh tính collection đích khác model đích: " + String.join(",", mismatches);
        }
        return null;
    }

    private void finish(EmbeddingReindexRun run, EmbeddingReindexStatus status, String errorMessage) {
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    private static InternalEmbeddingReindexClaimResponse toClaimResponse(EmbeddingReindexRun run, ChatModel model) {
        CandidateCredential credential = new CandidateCredential(
                model.getId(),
                model.getModelPurpose(),
                model.getSourceType(),
                model.getLlmProvider(),
                model.getLlmModelName(),
                model.getModelSourceRef(),
                model.getApiBaseUrl(),
                model.getApiKeyEncrypted(),
                model.getMaxRpm(),
                model.getMaxConcurrency());
        return new InternalEmbeddingReindexClaimResponse(
                run.getId(),
                run.getLeaseToken(),
                run.getLeaseUntil(),
                run.getSourceCollection(),
                run.getTargetCollection(),
                credential,
                model.getRevision(),
                model.getEmbeddingDimension(),
                model.getEmbeddingFingerprint());
    }
}
