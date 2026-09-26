package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.internal.InternalEmbeddingIndexIdentityRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.EmbeddingIndexIdentity;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.repository.EmbeddingIndexIdentityRepository;
import com.unisage.backend.repository.EmbeddingIndexIdentityWriter;
import com.unisage.backend.service.chatmodel.ChatModelServiceImpl;
import com.unisage.backend.utils.SsrfGuard;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Embedding identity guard from plan.md "Embedding identity guard". Enabled once the identity
 * comparison exists on activate/swap (Task 3) and on promote (also Task 3's
 * {@code ChatModelCandidatePromotionService} — the CAS/decision logic, even though no HTTP
 * endpoint reaches it yet; Task 6 owns wiring claim/result to it).
 */
class EmbeddingIdentityGuardTest {

    private static final String COLLECTION = "unisage_chunks";

    private ChatModelRepository chatModelRepository;
    private ChatModelVerificationRepository chatModelVerificationRepository;
    private EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private ModelRegistryVersionService modelRegistryVersionService;
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private ChatModelServiceImpl chatModelService;
    private ChatModelCandidatePromotionService promotionService;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelVerificationRepository = mock(ChatModelVerificationRepository.class);
        embeddingIndexIdentityService = mock(EmbeddingIndexIdentityService.class);
        modelRegistryVersionService = mock(ModelRegistryVersionService.class);
        applicationEventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        chatModelService = new ChatModelServiceImpl(
                chatModelRepository, chatModelVerificationRepository, new SsrfGuard(),
                embeddingIndexIdentityService, modelRegistryVersionService, applicationEventPublisher);
        promotionService = new ChatModelCandidatePromotionServiceImpl(
                chatModelRepository, chatModelVerificationRepository,
                embeddingIndexIdentityService, modelRegistryVersionService);
        ReflectionTestUtils.setField(chatModelService, "embeddingCollectionName", COLLECTION);
        ReflectionTestUtils.setField(promotionService, "embeddingCollectionName", COLLECTION);

        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatModelVerificationRepository.save(any(ChatModelVerification.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChatModel embeddingRow(ChatModelStatus status, boolean everVerified) {
        ChatModel model = ChatModel.builder()
                .id(UUID.randomUUID())
                .modelPurpose(ChatModelPurpose.EMBEDDING)
                .status(status)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("text-embedding-3-small")
                .apiBaseUrl("https://api.openai.com/v1")
                .apiKeyEncrypted("sk-old")
                .embeddingDimension(1536)
                .embeddingFingerprint(fp(1f, 0f, 0f))
                .revision(1)
                .candidateGeneration(1)
                .verifiedAt(everVerified ? LocalDateTime.now() : null)
                .build();
        model.setIsActive(true);
        return model;
    }

    private static Float[] fp(float... values) {
        Float[] out = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    private InternalEmbeddingIndexIdentityResponse establishedIdentity(ChatModel matching) {
        return new InternalEmbeddingIndexIdentityResponse(
                COLLECTION, matching.getLlmProvider(), matching.getLlmModelName(), matching.getModelSourceRef(),
                matching.getApiBaseUrl(), matching.getEmbeddingDimension(), matching.getEmbeddingFingerprint(),
                LocalDateTime.now(), "first-upsert");
    }

    private ChatModelVerification jobFor(ChatModel row) {
        return ChatModelVerification.builder()
                .id(UUID.randomUUID())
                .chatModel(row)
                .status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(row.getCandidateGeneration())
                .baseRevision(row.getRevision())
                .candidateLlmProvider(row.getLlmProvider())
                .candidateLlmModelName(row.getLlmModelName())
                .candidateModelSourceRef(row.getModelSourceRef())
                .candidateApiKeyEncrypted("sk-new")
                .candidateApiBaseUrl(row.getApiBaseUrl())
                .embeddingDimension(row.getEmbeddingDimension())
                .embeddingFingerprint(row.getEmbeddingFingerprint())
                .attempt(1)
                .maxAttempts(3)
                .build();
    }

    @Test
    void activeEmbedding_candidateChangesModelName_verifyOk_becomesReindexRequired_rowUnchanged() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setCandidateLlmModelName("text-embedding-3-large");
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
        assertThat(job.getStatus()).isEqualTo(ChatModelVerificationStatus.REINDEX_REQUIRED);
        assertThat(row.getLlmModelName()).isEqualTo("text-embedding-3-small"); // unchanged
        verify(chatModelRepository, never()).promoteCandidate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(modelRegistryVersionService, never()).bump();
    }

    @Test
    void activeEmbedding_candidateChangesProvider_becomesReindexRequired() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setCandidateLlmProvider("anthropic");
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
    }

    @Test
    void activeEmbedding_candidateChangesApiBaseUrl_becomesReindexRequired() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setCandidateApiBaseUrl("https://self-hosted.example.com/v1");
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
    }

    @Test
    void activeEmbedding_candidateChangesModelSourceRef_becomesReindexRequired() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setCandidateModelSourceRef("some/other-ref");
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
    }

    @Test
    void activeEmbedding_candidateDimensionDiffers_becomesReindexRequired() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setEmbeddingDimension(3072); // measured different from the row's/established 1536
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(row)));

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
    }

    @Test
    void activeEmbedding_onlyKeyChanges_fingerprintMatches_promotes() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row); // candidate identity fields identical to row; only key differs
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(row)));
        when(chatModelRepository.promoteCandidate(eq(row.getId()), any(), any(), any(), eq("sk-new"), any(),
                eq(1536), (Float[]) any(), eq(ChatModelStatus.ACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void activeEmbedding_onlyKeyChanges_fingerprintDiffers_becomesReindexRequired() {
        ChatModel row = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setEmbeddingFingerprint(fp(0f, 1f, 0f)); // orthogonal to the row's/established (1,0,0)
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(row)));

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.REINDEX_REQUIRED);
    }

    @Test
    void inactiveEmbedding_modelChange_promotesNormally_notInSnapshot() {
        ChatModel row = embeddingRow(ChatModelStatus.INACTIVE, true);
        ChatModelVerification job = jobFor(row);
        job.setCandidateLlmModelName("text-embedding-3-large"); // identity guard doesn't apply — row isn't ACTIVE
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(chatModelRepository.promoteCandidate(eq(row.getId()), any(), any(), any(), any(), any(),
                any(), (Float[]) any(), eq(ChatModelStatus.INACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        assertThat(promotionService.promote(job)).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
        verify(embeddingIndexIdentityService, never()).getIdentity(any());
        // Row lands at INACTIVE (not ACTIVE) -- not in the snapshot, so no bump.
        verify(modelRegistryVersionService, never()).bump();
    }

    @Test
    void inactiveEmbedding_afterModelChange_activate_rejected409ReindexRequired() {
        ChatModel establishedFor = embeddingRow(ChatModelStatus.ACTIVE, true); // the identity the index was built from
        ChatModel row = embeddingRow(ChatModelStatus.INACTIVE, true);
        row.setLlmModelName("text-embedding-3-large"); // changed after promote, index identity still the old model
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(establishedFor)));

        assertThatThrownBy(() -> chatModelService.updateStatus(row.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMBEDDING_REINDEX_REQUIRED);
    }

    @Test
    void swapActivate_differentIdentityWhileOtherActive_rejected409_originalStaysActive() {
        ChatModel activeA = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModel candidateB = embeddingRow(ChatModelStatus.INACTIVE, true);
        candidateB.setLlmModelName("text-embedding-3-large");
        when(chatModelRepository.findById(candidateB.getId())).thenReturn(Optional.of(candidateB));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(activeA)));

        assertThatThrownBy(() -> chatModelService.updateStatus(candidateB.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMBEDDING_REINDEX_REQUIRED);
        verify(chatModelRepository, never()).deactivateOtherActiveEmbedding(any());
        assertThat(activeA.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
    }

    @Test
    void swapActivate_sameIdentity_succeeds() {
        ChatModel activeA = embeddingRow(ChatModelStatus.ACTIVE, true);
        ChatModel candidateB = embeddingRow(ChatModelStatus.INACTIVE, true); // same identity fields as activeA
        when(chatModelRepository.findById(candidateB.getId())).thenReturn(Optional.of(candidateB));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(activeA)));
        when(chatModelRepository.updateStatusIfCurrent(candidateB.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE))
                .thenReturn(1);

        ChatModelResponse response = chatModelService.updateStatus(candidateB.getId(), ChatModelStatus.ACTIVE);

        assertThat(response).isNotNull();
        verify(chatModelRepository).deactivateOtherActiveEmbedding(candidateB.getId());
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void disabledRow_reactivateAfterKeyFix_sameIdentity_allowed() {
        // DISABLED can't activate directly (must re-verify first) — but once a re-verify promotes
        // it, promote() puts EMBEDDING rows at INACTIVE, and INACTIVE -> ACTIVE (this test) is what
        // "reactivate" actually looks like from here.
        ChatModel row = embeddingRow(ChatModelStatus.INACTIVE, true);
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(embeddingIndexIdentityService.getIdentity(COLLECTION)).thenReturn(Optional.of(establishedIdentity(row)));
        when(chatModelRepository.updateStatusIfCurrent(row.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE))
                .thenReturn(1);

        chatModelService.updateStatus(row.getId(), ChatModelStatus.ACTIVE);

        verify(modelRegistryVersionService).bump();
    }

    @Test
    void disabledRow_reactivateWithDifferentIdentity_rejected409() {
        ChatModel row = embeddingRow(ChatModelStatus.DISABLED, true); // never verified again after DISABLED
        when(chatModelRepository.findById(row.getId())).thenReturn(Optional.of(row));

        // DISABLED must re-verify first — activate is rejected before identity is even checked.
        assertThatThrownBy(() -> chatModelService.updateStatus(row.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
    }

    @Test
    void putIdentity_secondCallSameCollection_409_identityUnchangedRegardlessOfBody() {
        EmbeddingIndexIdentityRepository repository = mock(EmbeddingIndexIdentityRepository.class);
        EmbeddingIndexIdentityWriter writer = mock(EmbeddingIndexIdentityWriter.class);
        EmbeddingIndexIdentityServiceImpl service = new EmbeddingIndexIdentityServiceImpl(repository, writer);

        when(writer.insertIfAbsent(any())).thenReturn(false); // already exists
        InternalEmbeddingIndexIdentityRequest differentBody = new InternalEmbeddingIndexIdentityRequest(
                "anthropic", "some-other-model", null, null, 1024, fp(9f, 9f, 9f), "first-upsert");

        assertThatThrownBy(() -> service.putIdentity(COLLECTION, differentBody))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMBEDDING_INDEX_IDENTITY_EXISTS);

        verify(repository, never()).findByCollectionName(COLLECTION);
    }
}
