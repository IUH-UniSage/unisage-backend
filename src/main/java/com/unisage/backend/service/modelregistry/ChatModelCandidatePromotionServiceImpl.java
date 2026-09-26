package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.utils.EmbeddingFingerprintMatcher;

import lombok.RequiredArgsConstructor;

/**
 * See {@link ChatModelCandidatePromotionService}. Must run inside the caller's transaction
 * ({@code Propagation.MANDATORY}) — the job status write and the row promotion are one atomic
 * unit (plan.md R4.1: "Idempotency cùng transaction với promote").
 */
@Service
@RequiredArgsConstructor
public class ChatModelCandidatePromotionServiceImpl implements ChatModelCandidatePromotionService {

    private final ChatModelRepository chatModelRepository;
    private final ChatModelVerificationRepository chatModelVerificationRepository;
    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private final ModelRegistryVersionService modelRegistryVersionService;

    @Value("${model-registry.embedding-collection-name:unisage_chunks}")
    private String embeddingCollectionName;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome promote(ChatModelVerification job) {
        ChatModel row = chatModelRepository.findById(job.getChatModel().getId())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        boolean isEmbedding = row.getModelPurpose() == ChatModelPurpose.EMBEDDING;
        boolean rowCurrentlyActive = row.getStatus() == ChatModelStatus.ACTIVE;

        if (isEmbedding && rowCurrentlyActive && wouldChangeEmbeddingIdentity(row, job)) {
            job.setStatus(ChatModelVerificationStatus.REINDEX_REQUIRED);
            job.setFinishedAt(LocalDateTime.now());
            // saveAndFlush, not save: a caller composing this with another
            // clearAutomatically bulk update in the SAME transaction (e.g.
            // ModelRegistryIntegrationSeeder promoting then activating) would
            // otherwise silently lose this merge when that later query clears
            // the persistence context before this pending change is flushed.
            chatModelVerificationRepository.saveAndFlush(job);
            // Row untouched, no version bump, no event — plan.md "Embedding identity guard".
            return Outcome.REINDEX_REQUIRED;
        }

        ChatModelStatus newStatus = switch (row.getStatus()) {
            case PENDING, DISABLED -> isEmbedding ? ChatModelStatus.INACTIVE : ChatModelStatus.ACTIVE;
            case ACTIVE, INACTIVE -> row.getStatus();
        };

        int updated = chatModelRepository.promoteCandidate(
                row.getId(),
                job.getCandidateLlmProvider(),
                job.getCandidateLlmModelName(),
                job.getCandidateModelSourceRef(),
                job.getCandidateApiKeyEncrypted(),
                job.getCandidateApiBaseUrl(),
                job.getEmbeddingDimension(),
                job.getEmbeddingFingerprint(),
                newStatus,
                job.getCandidateGeneration(),
                job.getBaseRevision());

        if (updated == 0) {
            job.setStatus(ChatModelVerificationStatus.SUPERSEDED);
            job.setFinishedAt(LocalDateTime.now());
            // saveAndFlush - see the REINDEX_REQUIRED branch's comment above;
            // promoteCandidate() already cleared the persistence context, so
            // this merge needs to be flushed explicitly rather than left
            // pending for a commit (or a later clearAutomatically query) that
            // may or may not still be ahead of it.
            chatModelVerificationRepository.saveAndFlush(job);
            return Outcome.SUPERSEDED;
        }

        job.setStatus(ChatModelVerificationStatus.SUCCEEDED);
        job.setFinishedAt(LocalDateTime.now());
        chatModelVerificationRepository.saveAndFlush(job);
        // Only bump when the row is (or becomes) part of the snapshot — an EMBEDDING row promoted
        // onto PENDING/DISABLED lands at INACTIVE (never in the snapshot), and INACTIVE -> INACTIVE
        // stays out of it too. ACTIVE -> ACTIVE (key/candidate applied to an already-live row) and
        // PENDING/DISABLED -> ACTIVE (CHAT/EXTRACTION) both change what Python would read, so bump.
        if (newStatus == ChatModelStatus.ACTIVE) {
            modelRegistryVersionService.bump();
        }
        return Outcome.PROMOTED;
    }

    /**
     * plan.md "Embedding identity guard" table: a key-only rotation (every other credential field
     * identical to the row's current value) is allowed to promote only if the candidate's measured
     * dimension/fingerprint still matches the established index identity; any actual identity field
     * change (provider/model/sourceRef/baseUrl), or a measured mismatch, never auto-promotes.
     */
    private boolean wouldChangeEmbeddingIdentity(ChatModel row, ChatModelVerification job) {
        boolean identityFieldsChanged =
                !Objects.equals(row.getLlmProvider(), job.getCandidateLlmProvider())
                || !Objects.equals(row.getLlmModelName(), job.getCandidateLlmModelName())
                || !Objects.equals(row.getModelSourceRef(), job.getCandidateModelSourceRef())
                || !Objects.equals(row.getApiBaseUrl(), job.getCandidateApiBaseUrl());
        if (identityFieldsChanged) {
            return true;
        }

        Optional<InternalEmbeddingIndexIdentityResponse> identity =
                embeddingIndexIdentityService.getIdentity(embeddingCollectionName);
        if (identity.isEmpty()) {
            // Nothing established yet to compare against — can't be a mismatch caused by this
            // candidate; the row was ACTIVE so an identity should already exist in practice, but
            // fail open rather than block a key-only rotation on a Java-side gap.
            return false;
        }

        InternalEmbeddingIndexIdentityResponse established = identity.get();
        if (!Objects.equals(job.getEmbeddingDimension(), established.dimension())) {
            return true;
        }
        return !EmbeddingFingerprintMatcher.matches(job.getEmbeddingFingerprint(), established.fingerprint());
    }
}
