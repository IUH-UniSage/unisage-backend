package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.request.ChatModelUpdateRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.ChatModelVerificationSummary;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.service.embeddingindexidentity.EmbeddingIndexIdentityService;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.event.VerificationRequestedEvent;
import com.unisage.backend.utils.EmbeddingFingerprintMatcher;
import com.unisage.backend.utils.SsrfGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatModelServiceImpl implements ChatModelService {

    /**
     * Providers Python's provider factory has empirically proven accept an injected,
     * SSRF-pinned http_client (see {@code app/core/llm/provider_models.py} in unisage-agent).
     * "anthropic" is deliberately excluded: its SDK now requires an httpx2 client, which this
     * codebase has no SSRF-pinned equivalent for — allowing it here would let an SA create a
     * credential Python can never actually use. Revisit if/when that gap is closed.
     */
    public static final Set<String> SUPPORTED_LLM_PROVIDERS = Set.of("openai", "google", "groq", "mistral");

    /** Default {@code maxAttempts} for a freshly-created verification job (plan.md "Verification lifecycle"). */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ChatModelRepository chatModelRepository;
    private final ChatModelVerificationRepository chatModelVerificationRepository;
    private final SsrfGuard ssrfGuard;
    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private final ModelRegistryVersionService modelRegistryVersionService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Single-collection reality of the current system (plan.md "Embedding identity guard"). */
    @Value("${model-registry.embedding-collection-name:unisage_chunks}")
    private String embeddingCollectionName;

    @Override
    @Transactional
    public ChatModelResponse create(ChatModelRequest request) {
        validateBySourceType(request);

        ChatModel chatModel = ChatModel.builder()
                .modelPurpose(request.modelPurpose())
                .status(ChatModelStatus.PENDING)
                .revision(0)
                .candidateGeneration(1)
                .sourceType(request.sourceType())
                .llmProvider(request.llmProvider())
                .llmModelName(request.llmModelName())
                .modelSourceRef(request.modelSourceRef())
                .apiKeyEncrypted(request.apiKey())
                .apiBaseUrl(request.apiBaseUrl())
                .maxRpm(request.maxRpm())
                .priority(request.priority())
                .errorCount(0)
                .build();
        chatModel = chatModelRepository.save(chatModel);

        // plan.md "Credential rotation": a freshly-created row always starts with a QUEUED job
        // for its own (candidateGeneration=1, baseRevision=0) values — promote is what first
        // takes it out of PENDING.
        ChatModelVerification job = chatModelVerificationRepository.save(candidateJobFor(chatModel, 1, 0));
        applicationEventPublisher.publishEvent(new VerificationRequestedEvent(job.getId()));

        return mapToResponse(chatModel);
    }

    @Override
    @Transactional
    public ChatModelResponse update(UUID id, ChatModelUpdateRequest request) {
        ChatModel model = chatModelRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        ssrfGuard.validate(request.apiBaseUrl());

        boolean clearApiKey = Boolean.TRUE.equals(request.clearApiKey());
        if (clearApiKey && request.sourceType() != ChatModelSourceType.SELF_HOSTED) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("clearApiKey", "clearApiKey chỉ hợp lệ khi sourceType là SELF_HOSTED"));
        }

        // ── Resolve the apiKey tri/four-state (plan.md "Credential rotation") — the ONLY place
        // this is decided, per plan's explicit "xử lý ở đúng 1 chỗ duy nhất". ──────────────────
        boolean apiKeyProvided = request.apiKey() != null && request.apiKey().isPresent()
                && request.apiKey().get() != null;
        String newRawApiKey = null;
        if (apiKeyProvided) {
            newRawApiKey = request.apiKey().get();
            if (!StringUtils.hasText(newRawApiKey)) {
                throw new AppException(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED,
                        Map.of("apiKey", "apiKey không được là chuỗi rỗng hoặc toàn khoảng trắng"));
            }
        }
        // JsonNullable.of(null) (explicit JSON null) and undefined() (field absent) both mean
        // "keep the old key" — neither sets apiKeyProvided, so both fall through here.

        boolean effectiveHasKey = apiKeyProvided
                || (!clearApiKey && StringUtils.hasText(model.getApiKeyEncrypted()));

        boolean hostChanged = hostChanged(model.getApiBaseUrl(), request.apiBaseUrl());
        if (hostChanged && !apiKeyProvided && StringUtils.hasText(model.getApiKeyEncrypted())) {
            // Never let a stored key ride along to a host SA hasn't confirmed by retyping it.
            throw new AppException(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST);
        }

        validateCredentialShape(request.sourceType(), request.llmProvider(), effectiveHasKey);

        // ── Non-credential fields apply immediately, no verify needed (plan.md footnote: only
        // apiKey/apiBaseUrl/llmModelName/llmProvider/modelSourceRef are staged). ────────────────
        // Only `priority` bumps the registry version here: Python sorts credentials by priority
        // within a purpose, so a priority change can change routing order. `maxRpm` is parsed by
        // unisage-agent (app/core/model_registry.py) but nothing there reads it yet for
        // routing/rate-limiting (Task 9/10 territory, not built) — bumping on it today would only
        // be reload churn with no behavioral effect. Revisit this once Task 9/10 lands.
        boolean snapshotAffectingChange = !Objects.equals(model.getPriority(), request.priority());
        model.setSourceType(request.sourceType());
        model.setMaxRpm(request.maxRpm());
        model.setPriority(request.priority());

        // ── Credential fields — staged rotation, never written straight onto the row. ─────────
        boolean credentialChanged = !Objects.equals(model.getLlmProvider(), request.llmProvider())
                || !Objects.equals(model.getLlmModelName(), request.llmModelName())
                || !Objects.equals(model.getModelSourceRef(), request.modelSourceRef())
                || !Objects.equals(model.getApiBaseUrl(), request.apiBaseUrl())
                || apiKeyProvided
                || (clearApiKey && StringUtils.hasText(model.getApiKeyEncrypted()));

        if (credentialChanged) {
            chatModelVerificationRepository.supersedeOpenJobs(model.getId());
            int newGeneration = model.getCandidateGeneration() + 1;
            model.setCandidateGeneration(newGeneration);

            String candidateApiKey;
            if (apiKeyProvided) {
                candidateApiKey = newRawApiKey;
            } else if (clearApiKey) {
                candidateApiKey = null;
            } else {
                // Keep the old key: copy the value already held in memory rather than round-trip
                // it through the DTO — it never becomes a request/response field either way.
                candidateApiKey = model.getApiKeyEncrypted();
            }

            ChatModelVerification job = ChatModelVerification.builder()
                    .chatModel(model)
                    .status(ChatModelVerificationStatus.QUEUED)
                    .candidateGeneration(newGeneration)
                    .baseRevision(model.getRevision())
                    .candidateLlmProvider(request.llmProvider())
                    .candidateLlmModelName(request.llmModelName())
                    .candidateModelSourceRef(request.modelSourceRef())
                    .candidateApiKeyEncrypted(candidateApiKey)
                    .candidateApiBaseUrl(request.apiBaseUrl())
                    .attempt(0)
                    .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                    .build();
            ChatModelVerification savedJob = chatModelVerificationRepository.save(job);
            applicationEventPublisher.publishEvent(new VerificationRequestedEvent(savedJob.getId()));
        }

        // Reassign to save()'s return value, not the pre-existing `model` reference: when
        // credentialChanged, supersedeOpenJobs() above (@Modifying(clearAutomatically = true))
        // already cleared the persistence context, so `model` is now detached. save() on a
        // detached entity with an id merges it into a NEW managed instance instead of updating
        // `model` in place - mapToResponse() below lazy-loads model.getUpdatedBy().getFullName(),
        // which throws LazyInitializationException on the stale detached reference once
        // updatedBy is non-null (i.e. from this row's second update onward - the very first
        // update was masked because updatedBy still read null from the pre-detach load).
        model = chatModelRepository.save(model);
        if (snapshotAffectingChange) {
            modelRegistryVersionService.bump();
        }
        return mapToResponse(model);
    }

    /** Same shape as {@link #validateBySourceType} but update's apiKey rule is "effective", not "field present". */
    private void validateCredentialShape(ChatModelSourceType sourceType, String llmProvider, boolean effectiveHasKey) {
        if (sourceType == ChatModelSourceType.CLOUD_API) {
            if (!StringUtils.hasText(llmProvider)) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_REQUIRED);
            }
            if (!SUPPORTED_LLM_PROVIDERS.contains(llmProvider.toLowerCase(Locale.ROOT))) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_UNSUPPORTED);
            }
            if (!effectiveHasKey) {
                throw new AppException(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED);
            }
        }
    }

    private boolean hostChanged(String oldUrl, String newUrl) {
        if (Objects.equals(oldUrl, newUrl)) {
            return false;
        }
        String oldHost = extractHost(oldUrl);
        String newHost = extractHost(newUrl);
        return !Objects.equals(oldHost, newHost);
    }

    private String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : url.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return url.toLowerCase(Locale.ROOT);
        }
    }

    private void validateBySourceType(ChatModelRequest request) {
        if (request.sourceType() == ChatModelSourceType.CLOUD_API) {
            if (!StringUtils.hasText(request.llmProvider())) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_REQUIRED);
            }
            if (!SUPPORTED_LLM_PROVIDERS.contains(request.llmProvider().toLowerCase(Locale.ROOT))) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_UNSUPPORTED);
            }
            if (!StringUtils.hasText(request.apiKey())) {
                throw new AppException(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED);
            }
        }
        // SELF_HOSTED: apiKey và modelSourceRef đều là tuỳ chọn, modelSourceRef không bị ràng buộc định dạng.
        ssrfGuard.validate(request.apiBaseUrl());
    }

    @Override
    public ChatModelResponse getById(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        return mapToResponse(chatModel);
    }

    @Override
    public PageResponse<List<ChatModelResponse>> getAll(ChatModelPurpose modelPurpose, ChatModelStatus status, Pageable pageable) {
        Page<ChatModel> page = chatModelRepository.findAllFiltered(modelPurpose, status, pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    /**
     * Soft-delete. Valid from any status (plan.md "State machine" — the "Delete" column has no
     * rejected cell) — moves the row to {@code INACTIVE} and out of the routing snapshot, same as
     * a manual deactivate, plus {@code isActive = false}. Any verification job still open for this
     * row is cancelled in the same transaction; Task 6 owns the claim/result lifecycle but there's
     * no reason to leave a claimable job pointing at a soft-deleted credential in the meantime.
     */
    @Override
    @Transactional
    public void delete(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        boolean wasActive = chatModel.getStatus() == ChatModelStatus.ACTIVE;
        chatModel.setIsActive(false);
        chatModel.setStatus(ChatModelStatus.INACTIVE);
        chatModelRepository.save(chatModel);
        cancelOpenJobs(id);
        if (wasActive) {
            modelRegistryVersionService.bump();
        }
    }

    private void cancelOpenJobs(UUID chatModelId) {
        List<ChatModelVerification> openJobs = chatModelVerificationRepository.findByChatModelIdAndStatusIn(
                chatModelId, List.of(ChatModelVerificationStatus.QUEUED, ChatModelVerificationStatus.RUNNING));
        for (ChatModelVerification job : openJobs) {
            job.setStatus(ChatModelVerificationStatus.CANCELLED);
        }
        chatModelVerificationRepository.saveAll(openJobs);
    }

    /**
     * Undoes a soft-delete. Restores {@code isActive = true} but deliberately leaves {@code status}
     * at {@code INACTIVE} — recovering a row must never put it back in the routing snapshot by
     * itself; SA has to activate it explicitly afterwards (plan.md "State machine", row
     * "(is_active=false)" / column "Recover").
     */
    @Override
    @Transactional
    public void recover(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        chatModel.setIsActive(true);
        chatModelRepository.save(chatModel);
    }

    @Override
    @Transactional
    public ChatModelResponse updateStatus(UUID id, ChatModelStatus targetStatus) {
        if (targetStatus != ChatModelStatus.ACTIVE && targetStatus != ChatModelStatus.INACTIVE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("status", "status chỉ nhận ACTIVE hoặc INACTIVE"));
        }

        ChatModel model = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        if (targetStatus == ChatModelStatus.ACTIVE) {
            activate(model);
        } else {
            int updated = chatModelRepository.deactivate(model.getId());
            if (updated == 0) {
                throw new AppException(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
            }
            modelRegistryVersionService.bump();
        }

        ChatModel refreshed = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        return mapToResponse(refreshed);
    }

    /**
     * plan.md "State machine": PENDING (never verified) → {@code CHAT_MODEL_NOT_VERIFIED}; DISABLED
     * (must re-verify first) and ACTIVE (no-op transition) → {@code CHAT_MODEL_STATUS_CONFLICT};
     * INACTIVE with a prior successful verify → allowed, with the EMBEDDING identity guard applied
     * on top for that purpose (covers plain activate, swap between credentials, and reactivating
     * after DISABLED — all three land here as "INACTIVE -> ACTIVE").
     */
    private void activate(ChatModel model) {
        if (model.getVerifiedAt() == null) {
            throw new AppException(ErrorCode.CHAT_MODEL_NOT_VERIFIED);
        }
        if (model.getStatus() != ChatModelStatus.INACTIVE) {
            throw new AppException(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
        }

        if (model.getModelPurpose() == ChatModelPurpose.EMBEDDING) {
            activateEmbedding(model);
        } else {
            int updated = chatModelRepository.updateStatusIfCurrent(
                    model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE);
            if (updated == 0) {
                throw new AppException(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
            }
            modelRegistryVersionService.bump();
        }
    }

    /**
     * plan.md "Embedding identity guard" + "State machine" race handling. Identity mismatch is
     * checked and rejected *before* any write. A match (or no identity established yet — Java has
     * no way to independently confirm the Qdrant collection is empty, so an absent identity is
     * treated as "nothing to conflict with") proceeds to the swap: deactivate whatever EMBEDDING
     * row is currently ACTIVE, then activate this one, both inside the current transaction. A
     * concurrent racer loses to the unique partial index, surfacing here as
     * {@link DataIntegrityViolationException}, mapped to {@code EMBEDDING_ACTIVE_CONFLICT} rather
     * than propagating as a 500.
     */
    private void activateEmbedding(ChatModel model) {
        Optional<InternalEmbeddingIndexIdentityResponse> identity =
                embeddingIndexIdentityService.getIdentity(embeddingCollectionName);
        if (identity.isPresent()) {
            List<String> mismatches = embeddingIdentityMismatches(model, identity.get());
            if (!mismatches.isEmpty()) {
                throw new AppException(ErrorCode.EMBEDDING_REINDEX_REQUIRED,
                        Map.of("mismatchedFields", String.join(",", mismatches)));
            }
        }

        try {
            chatModelRepository.deactivateOtherActiveEmbedding(model.getId());
            int updated = chatModelRepository.updateStatusIfCurrent(
                    model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE);
            if (updated == 0) {
                throw new AppException(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.EMBEDDING_ACTIVE_CONFLICT);
        }
        modelRegistryVersionService.bump();
    }

    private List<String> embeddingIdentityMismatches(ChatModel model, InternalEmbeddingIndexIdentityResponse identity) {
        List<String> mismatches = new java.util.ArrayList<>();
        if (!Objects.equals(model.getLlmProvider(), identity.provider())) {
            mismatches.add("provider");
        }
        if (!Objects.equals(model.getLlmModelName(), identity.modelName())) {
            mismatches.add("modelName");
        }
        if (!Objects.equals(model.getModelSourceRef(), identity.modelSourceRef())) {
            mismatches.add("modelSourceRef");
        }
        if (!Objects.equals(model.getApiBaseUrl(), identity.apiBaseUrl())) {
            mismatches.add("apiBaseUrl");
        }
        if (!Objects.equals(model.getEmbeddingDimension(), identity.dimension())) {
            mismatches.add("dimension");
        }
        if (!EmbeddingFingerprintMatcher.matches(model.getEmbeddingFingerprint(), identity.fingerprint())) {
            mismatches.add("fingerprint");
        }
        return mismatches;
    }

    @Override
    @Transactional
    public ChatModelResponse updatePriority(UUID id, Integer priority) {
        ChatModel model = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        model.setPriority(priority);
        chatModelRepository.save(model);
        modelRegistryVersionService.bump();
        return mapToResponse(model);
    }

    @Override
    @Transactional
    public ChatModelResponse verify(UUID id) {
        ChatModel model = chatModelRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        chatModelVerificationRepository.supersedeOpenJobs(model.getId());
        int newGeneration = model.getCandidateGeneration() + 1;
        model.setCandidateGeneration(newGeneration);
        chatModelRepository.save(model);

        ChatModelVerification job = chatModelVerificationRepository.save(
                candidateJobFor(model, newGeneration, model.getRevision()));
        applicationEventPublisher.publishEvent(new VerificationRequestedEvent(job.getId()));

        return mapToResponse(model);
    }

    /** A job whose candidate is exactly the row's current values — used by {@code create} and {@code verify}. */
    private ChatModelVerification candidateJobFor(ChatModel model, int generation, int baseRevision) {
        return ChatModelVerification.builder()
                .chatModel(model)
                .status(ChatModelVerificationStatus.QUEUED)
                .candidateGeneration(generation)
                .baseRevision(baseRevision)
                .candidateLlmProvider(model.getLlmProvider())
                .candidateLlmModelName(model.getLlmModelName())
                .candidateModelSourceRef(model.getModelSourceRef())
                .candidateApiKeyEncrypted(model.getApiKeyEncrypted())
                .candidateApiBaseUrl(model.getApiBaseUrl())
                .attempt(0)
                .maxAttempts(DEFAULT_MAX_ATTEMPTS)
                .build();
    }

    private ChatModelResponse mapToResponse(ChatModel chatModel) {
        ChatModelVerification latestJob = chatModelVerificationRepository
                .findFirstByChatModelIdOrderByCreatedAtDesc(chatModel.getId())
                .orElse(null);
        boolean hasPendingChange = latestJob != null
                && (latestJob.getStatus() == ChatModelVerificationStatus.QUEUED
                    || latestJob.getStatus() == ChatModelVerificationStatus.RUNNING);

        return ChatModelResponse.builder()
                .id(chatModel.getId())
                .modelPurpose(chatModel.getModelPurpose())
                .status(chatModel.getStatus())
                .revision(chatModel.getRevision())
                .verifiedAt(chatModel.getVerifiedAt())
                .sourceType(chatModel.getSourceType())
                .llmProvider(chatModel.getLlmProvider())
                .llmModelName(chatModel.getLlmModelName())
                .modelSourceRef(chatModel.getModelSourceRef())
                .hasApiKey(StringUtils.hasText(chatModel.getApiKeyEncrypted()))
                .apiBaseUrl(chatModel.getApiBaseUrl())
                .maxRpm(chatModel.getMaxRpm())
                .priority(chatModel.getPriority())
                .errorCount(chatModel.getErrorCount())
                .lastErrorAt(chatModel.getLastErrorAt())
                .lastErrorCode(chatModel.getLastErrorCode())
                .hasPendingChange(hasPendingChange)
                .latestVerification(latestJob != null ? toVerificationSummary(latestJob) : null)
                .isActive(chatModel.getIsActive())
                .createdAt(chatModel.getCreatedAt())
                .createdBy(chatModel.getCreatedBy() != null ? chatModel.getCreatedBy().getId().toString() : null)
                .createdByName(chatModel.getCreatedBy() != null ? chatModel.getCreatedBy().getFullName() : null)
                .updatedAt(chatModel.getUpdatedAt())
                .updatedBy(chatModel.getUpdatedBy() != null ? chatModel.getUpdatedBy().getId().toString() : null)
                .updatedByName(chatModel.getUpdatedBy() != null ? chatModel.getUpdatedBy().getFullName() : null)
                .build();
    }

    private ChatModelVerificationSummary toVerificationSummary(ChatModelVerification job) {
        return ChatModelVerificationSummary.builder()
                .id(job.getId())
                .status(job.getStatus())
                .attempt(job.getAttempt())
                .errorType(job.getErrorType())
                .errorCode(job.getErrorCode())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }
}
