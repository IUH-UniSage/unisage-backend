package com.unisage.backend.service.embeddingreindex;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.EmbeddingReindexRequest;
import com.unisage.backend.dto.response.EmbeddingIndexResponse;
import com.unisage.backend.dto.response.EmbeddingReindexRunResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.EmbeddingReindexRun;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.EmbeddingReindexStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.EmbeddingReindexRunRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.embeddingindexpointer.EmbeddingIndexPointerService;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.utils.EmbeddingIdentityMismatches;
import com.unisage.backend.utils.SecurityUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmbeddingReindexServiceImpl implements EmbeddingReindexService {

    static final List<EmbeddingReindexStatus> OPEN_STATUSES =
            List.of(EmbeddingReindexStatus.QUEUED, EmbeddingReindexStatus.RUNNING);

    private static final DateTimeFormatter TARGET_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EmbeddingReindexRunRepository runRepository;
    private final ChatModelRepository chatModelRepository;
    private final UserRepository userRepository;
    private final EmbeddingIndexPointerService pointerService;
    private final ModelRegistryVersionService modelRegistryVersionService;
    private final SecurityUtil securityUtil;

    @Override
    @Transactional(readOnly = true)
    public EmbeddingIndexResponse getIndex() {
        String base = pointerService.baseCollection();
        EmbeddingIndexResponse.ActiveIdentity identity = pointerService.activeIdentity()
                .map(i -> new EmbeddingIndexResponse.ActiveIdentity(
                        i.provider(), i.modelName(), i.dimension(), i.establishedAt()))
                .orElse(null);
        EmbeddingIndexResponse.ActiveModel activeModel = chatModelRepository.findAllActiveForSnapshot().stream()
                .filter(m -> m.getModelPurpose() == ChatModelPurpose.EMBEDDING)
                .findFirst()
                .map(m -> new EmbeddingIndexResponse.ActiveModel(
                        m.getId(), m.getDisplayName(), m.getLlmProvider(), m.getLlmModelName()))
                .orElse(null);
        return EmbeddingIndexResponse.builder()
                .baseCollection(base)
                .activeCollection(pointerService.activeCollection())
                .activeIdentity(identity)
                .activeModel(activeModel)
                .currentRun(runRepository.findFirstByBaseCollectionAndStatusInOrderByCreatedAtDesc(base, OPEN_STATUSES)
                        .map(EmbeddingReindexRunMapper::toResponse)
                        .orElse(null))
                .lastRun(runRepository.findFirstByBaseCollectionOrderByCreatedAtDesc(base)
                        .map(EmbeddingReindexRunMapper::toResponse)
                        .orElse(null))
                .build();
    }

    @Override
    @Transactional
    public EmbeddingReindexRunResponse start(EmbeddingReindexRequest request) {
        ChatModel model = chatModelRepository.findByIdForUpdate(request.chatModelId())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        if (!isReindexTarget(model)) {
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_MODEL_INVALID);
        }

        String base = pointerService.baseCollection();
        if (runRepository.findFirstByBaseCollectionAndStatusInOrderByCreatedAtDesc(base, OPEN_STATUSES).isPresent()) {
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_IN_PROGRESS);
        }

        // Same identity (or nothing indexed yet): the model can simply be activated.
        Optional<InternalEmbeddingIndexIdentityResponse> activeIdentity = pointerService.activeIdentity();
        if (activeIdentity.isEmpty() || EmbeddingIdentityMismatches.of(model, activeIdentity.get()).isEmpty()) {
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_NOT_NEEDED);
        }

        UUID requestedBy = securityUtil.getCurrentUserIdOrNull();
        EmbeddingReindexRun run = EmbeddingReindexRun.builder()
                .baseCollection(base)
                .sourceCollection(pointerService.activeCollection())
                .targetCollection(base + "__" + LocalDateTime.now(ZoneOffset.UTC).format(TARGET_SUFFIX))
                .targetChatModel(model)
                .targetModelRevision(model.getRevision())
                .status(EmbeddingReindexStatus.QUEUED)
                .requestedBy(requestedBy != null ? userRepository.getReferenceById(requestedBy) : null)
                .build();
        try {
            run = runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            // ux_embedding_reindex_runs_open: a concurrent start won.
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_IN_PROGRESS);
        }
        // The snapshot now carries the open run: Python refuses new embeds and mirrors payload
        // edits/deletes into the target collection (plan.md D4).
        modelRegistryVersionService.bump();
        return EmbeddingReindexRunMapper.toResponse(run);
    }

    @Override
    @Transactional
    public EmbeddingReindexRunResponse cancel(UUID runId) {
        EmbeddingReindexRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new AppException(ErrorCode.EMBEDDING_REINDEX_RUN_NOT_FOUND));
        if (!run.getStatus().isOpen()) {
            throw new AppException(ErrorCode.EMBEDDING_REINDEX_RUN_NOT_OPEN);
        }
        // The lease token stays: the agent's next progress report is answered CANCELLED, not 409,
        // so it knows to drop the target collection.
        run.setStatus(EmbeddingReindexStatus.CANCELLED);
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
        modelRegistryVersionService.bump();
        return EmbeddingReindexRunMapper.toResponse(run);
    }

    /** A verified, INACTIVE, non-deleted EMBEDDING row with a measured dimension and fingerprint. */
    static boolean isReindexTarget(ChatModel model) {
        return model.getModelPurpose() == ChatModelPurpose.EMBEDDING
                && Boolean.TRUE.equals(model.getIsActive())
                && model.getStatus() == ChatModelStatus.INACTIVE
                && model.getVerifiedAt() != null
                && model.getEmbeddingDimension() != null
                && model.getEmbeddingFingerprint() != null
                && model.getEmbeddingFingerprint().length > 0;
    }
}
