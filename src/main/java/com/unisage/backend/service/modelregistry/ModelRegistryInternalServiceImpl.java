package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistrySnapshotResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistrySnapshotResponse.CredentialEntry;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.CredentialHealthErrorType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.utils.SecretRedactor;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModelRegistryInternalServiceImpl implements ModelRegistryInternalService {

    private final ChatModelRepository chatModelRepository;
    private final ModelRegistryVersionService modelRegistryVersionService;
    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;

    /** Collection Python is configured against — same property key ChatModelServiceImpl uses. */
    @Value("${model-registry.embedding-collection-name:unisage_chunks}")
    private String embeddingCollectionName;

    /**
     * Backs plan.md "Internal API contract" endpoint #3. The revision check and the
     * counter/last-error write happen in one {@code UPDATE ... WHERE revision = :rev} (see
     * {@link ChatModelRepository#recordHealthError}) — a stale {@code credentialRevision} updates 0
     * rows and the whole report is ignored (no counter, no status change), same transaction, no
     * separate read first. Only once that succeeds does a {@code PERMANENT} report attempt the
     * {@code ACTIVE -> DISABLED} compare-and-set and, only if that actually flips the row, bump the
     * registry version in the same transaction.
     */
    @Override
    @Transactional
    public CredentialHealthReportResponse reportHealth(UUID chatModelId, CredentialHealthReportRequest request) {
        if (!chatModelRepository.existsById(chatModelId)) {
            throw new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND);
        }

        LocalDateTime occurredAt = request.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        // Redact even though unisage-agent already redacts its side — defense in depth, plan.md
        // "Secret redaction". SecretRedactor.redact also truncates to 500 chars, matching the column.
        String redactedMessage = request.message() != null ? SecretRedactor.redact(request.message()) : null;

        int applied = chatModelRepository.recordHealthError(
                chatModelId, request.credentialRevision(), occurredAt, request.errorCode(), redactedMessage);
        if (applied == 0) {
            // Stale revision — report ignored entirely, per plan.md R2.6.
            return new CredentialHealthReportResponse(false);
        }

        if (request.errorType() == CredentialHealthErrorType.PERMANENT) {
            // Already DISABLED/INACTIVE just no-ops here (0 rows) — still applied: true, still 200,
            // idempotent rather than an error. TRANSIENT never reaches this branch at all.
            int disabled = chatModelRepository.disableIfActive(chatModelId);
            if (disabled == 1) {
                // Only bump the registry version when the status actually flipped, so Python isn't
                // told to reload the snapshot on every TRANSIENT/duplicate report.
                modelRegistryVersionService.bump();
            }
        }

        return new CredentialHealthReportResponse(true);
    }

    /**
     * Version and rows are read in the same read-only {@code REPEATABLE_READ} transaction (plan.md
     * "Internal API contract") so a concurrent write can never be observed as "new version, old
     * data" or vice versa. Reading straight off {@link ChatModel}'s own columns — never a
     * verification job's {@code candidate_*} columns — is what makes acceptance criterion #7
     * (a row with a pending rotation candidate still reports its old, currently-active values)
     * automatic rather than something this method has to special-case.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InternalModelRegistrySnapshotResponse getSnapshot() {
        long version = modelRegistryVersionService.currentVersion();
        List<ChatModel> activeModels = chatModelRepository.findAllActiveForSnapshot();

        Map<ChatModelPurpose, List<CredentialEntry>> purposes = new EnumMap<>(ChatModelPurpose.class);
        for (ChatModelPurpose purpose : ChatModelPurpose.values()) {
            purposes.put(purpose, new java.util.ArrayList<>());
        }
        for (ChatModel model : activeModels) {
            purposes.get(model.getModelPurpose()).add(toCredentialEntry(model));
        }

        InternalEmbeddingIndexIdentityResponse embeddingIndexIdentity =
                embeddingIndexIdentityService.getIdentity(embeddingCollectionName).orElse(null);

        return new InternalModelRegistrySnapshotResponse(
                version, OffsetDateTime.now(ZoneOffset.UTC), purposes, embeddingIndexIdentity);
    }

    private CredentialEntry toCredentialEntry(ChatModel model) {
        return new CredentialEntry(
                model.getId(),
                model.getRevision(),
                model.getSourceType(),
                model.getLlmProvider(),
                model.getLlmModelName(),
                model.getApiBaseUrl(),
                model.getApiKeyEncrypted(),
                model.getPriority(),
                model.getMaxRpm());
    }
}
