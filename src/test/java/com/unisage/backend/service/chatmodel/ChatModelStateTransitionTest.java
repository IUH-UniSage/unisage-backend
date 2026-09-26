package com.unisage.backend.service.chatmodel;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.entity.enums.CredentialHealthErrorType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.service.modelregistry.ChatModelCandidatePromotionService;
import com.unisage.backend.service.modelregistry.ChatModelCandidatePromotionServiceImpl;
import com.unisage.backend.service.modelregistry.EmbeddingIndexIdentityService;
import com.unisage.backend.service.modelregistry.ModelRegistryInternalServiceImpl;
import com.unisage.backend.service.modelregistry.ModelRegistryVersionService;
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
 * State-transition matrix from plan.md "State machine". Names double as the scenario list.
 * The activate/deactivate/verify service methods landed in Task 3, so every scenario below is
 * enabled — only {@code VerificationFencingTest} stays disabled (Task 6: claim/result lifecycle).
 */
class ChatModelStateTransitionTest {

    private ChatModelRepository chatModelRepository;
    private ChatModelVerificationRepository chatModelVerificationRepository;
    private EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private ChatModelServiceImpl chatModelService;
    private ModelRegistryVersionService modelRegistryVersionService;
    private ModelRegistryInternalServiceImpl modelRegistryInternalService;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelVerificationRepository = mock(ChatModelVerificationRepository.class);
        embeddingIndexIdentityService = mock(EmbeddingIndexIdentityService.class);
        modelRegistryVersionService = mock(ModelRegistryVersionService.class);
        SsrfGuard ssrfGuard = new SsrfGuard();
        ReflectionTestUtils.setField(ssrfGuard, "allowlistRaw", "localhost");
        ReflectionTestUtils.setField(ssrfGuard, "dnsResolver", (SsrfGuard.DnsResolver) host -> {
            if ("localhost".equals(host)) {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")};
            }
            throw new java.net.UnknownHostException(host);
        });
        chatModelService = new ChatModelServiceImpl(
                chatModelRepository, chatModelVerificationRepository, ssrfGuard,
                embeddingIndexIdentityService, modelRegistryVersionService);
        // @Value fields aren't populated outside a Spring context — match the property's own default.
        ReflectionTestUtils.setField(chatModelService, "embeddingCollectionName", "unisage_chunks");
        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatModelVerificationRepository.save(any(ChatModelVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        modelRegistryInternalService = new ModelRegistryInternalServiceImpl(
                chatModelRepository, modelRegistryVersionService, embeddingIndexIdentityService);
        ReflectionTestUtils.setField(modelRegistryInternalService, "embeddingCollectionName", "unisage_chunks");
        promotionService = new ChatModelCandidatePromotionServiceImpl(
                chatModelRepository, chatModelVerificationRepository, embeddingIndexIdentityService, modelRegistryVersionService);
        ReflectionTestUtils.setField(promotionService, "embeddingCollectionName", "unisage_chunks");
    }

    private ChatModelCandidatePromotionService promotionService;

    private ChatModelVerification jobFor(ChatModel model) {
        return ChatModelVerification.builder()
                .id(UUID.randomUUID())
                .chatModel(model)
                .status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(model.getCandidateGeneration())
                .baseRevision(model.getRevision())
                .candidateLlmProvider(model.getLlmProvider())
                .candidateLlmModelName(model.getLlmModelName())
                .candidateModelSourceRef(model.getModelSourceRef())
                .candidateApiKeyEncrypted(model.getApiKeyEncrypted())
                .candidateApiBaseUrl(model.getApiBaseUrl())
                .attempt(1)
                .maxAttempts(3)
                .build();
    }

    private ChatModel modelWith(ChatModelStatus status, boolean isActive) {
        return modelWith(ChatModelPurpose.CHAT, status, isActive, status != ChatModelStatus.PENDING);
    }

    private ChatModel modelWith(ChatModelPurpose purpose, ChatModelStatus status, boolean isActive, boolean everVerified) {
        ChatModel model = ChatModel.builder()
                .id(UUID.randomUUID())
                .modelPurpose(purpose)
                .status(status)
                .revision(everVerified ? 1 : 0)
                .candidateGeneration(1)
                .verifiedAt(everVerified ? LocalDateTime.now() : null)
                .build();
        model.setIsActive(isActive);
        return model;
    }

    @Test
    void create_startsAtPending_revisionZero() {
        // Same assertion ChatModelServiceImplTest.create_setsPendingStatusAndRevisionZero already
        // makes on the response; this one additionally checks a QUEUED job was created.
        var request = com.unisage.backend.dto.request.ChatModelRequest.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .sourceType(com.unisage.backend.entity.enums.ChatModelSourceType.SELF_HOSTED)
                .llmModelName("mistral-7b")
                .apiBaseUrl("http://localhost:8000/v1")
                .maxRpm(60)
                .build();

        var response = chatModelService.create(request);

        assertThat(response.status()).isEqualTo(ChatModelStatus.PENDING);
        assertThat(response.revision()).isEqualTo(0);
        verify(chatModelVerificationRepository).save(argThatQueuedJob(1, 0));
    }

    private ChatModelVerification argThatQueuedJob(int generation, int baseRevision) {
        return org.mockito.ArgumentMatchers.argThat(job ->
                job != null
                && job.getStatus() == ChatModelVerificationStatus.QUEUED
                && job.getCandidateGeneration() == generation
                && job.getBaseRevision() == baseRevision);
    }

    @Test
    void pending_verifyOk_chatOrExtraction_becomesActive() {
        ChatModel model = modelWith(ChatModelPurpose.CHAT, ChatModelStatus.PENDING, true, false);
        ChatModelVerification job = jobFor(model);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.promoteCandidate(eq(model.getId()), any(), any(), any(), any(), any(), any(), (Float[]) any(),
                eq(ChatModelStatus.ACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
        assertThat(job.getStatus()).isEqualTo(ChatModelVerificationStatus.SUCCEEDED);
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void pending_verifyOk_embedding_becomesInactive() {
        ChatModel model = modelWith(ChatModelPurpose.EMBEDDING, ChatModelStatus.PENDING, true, false);
        ChatModelVerification job = jobFor(model);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.promoteCandidate(eq(model.getId()), any(), any(), any(), any(), any(), any(), (Float[]) any(),
                eq(ChatModelStatus.INACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
    }

    @Test
    void disabled_reVerify_verifyOk_becomesActive() {
        ChatModel model = modelWith(ChatModelPurpose.CHAT, ChatModelStatus.DISABLED, true, true);
        ChatModelVerification job = jobFor(model);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.promoteCandidate(eq(model.getId()), any(), any(), any(), any(), any(), any(), (Float[]) any(),
                eq(ChatModelStatus.ACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
    }

    @org.junit.jupiter.api.Disabled("Task 6 — needs the claim/result endpoint to report a verify failure")
    @Test
    void pending_verifyFail_hetRetry_staysPending() {
    }

    @Test
    void pending_activate_rejected409() {
        ChatModel model = modelWith(ChatModelStatus.PENDING, true);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_NOT_VERIFIED);
    }

    @Test
    void active_deactivate_becomesInactive() {
        ChatModel model = modelWith(ChatModelStatus.ACTIVE, true);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.deactivate(model.getId())).thenReturn(1);

        chatModelService.updateStatus(model.getId(), ChatModelStatus.INACTIVE);

        verify(chatModelRepository).deactivate(model.getId());
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void active_healthPermanent_becomesDisabled_correctRevision() {
        UUID id = UUID.randomUUID();
        when(chatModelRepository.existsById(id)).thenReturn(true);
        when(chatModelRepository.recordHealthError(eq(id), eq(3), any(), any(), any())).thenReturn(1);
        when(chatModelRepository.disableIfActive(id)).thenReturn(1);

        CredentialHealthReportRequest request = new CredentialHealthReportRequest(
                3, 10L, CredentialHealthErrorType.PERMANENT, "invalid_api_key", "key revoked", OffsetDateTime.now());

        CredentialHealthReportResponse response = modelRegistryInternalService.reportHealth(id, request);

        assertThat(response.applied()).isTrue();
        verify(chatModelRepository).recordHealthError(eq(id), eq(3), any(), eq("invalid_api_key"), any());
        verify(chatModelRepository).disableIfActive(id);
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void active_healthPermanent_staleRevision_ignored() {
        UUID id = UUID.randomUUID();
        when(chatModelRepository.existsById(id)).thenReturn(true);
        // Row is already on revision 4 (rotated since); report still carries the old revision 3.
        when(chatModelRepository.recordHealthError(eq(id), eq(3), any(), any(), any())).thenReturn(0);

        CredentialHealthReportRequest request = new CredentialHealthReportRequest(
                3, 10L, CredentialHealthErrorType.PERMANENT, "invalid_api_key", "key revoked", OffsetDateTime.now());

        CredentialHealthReportResponse response = modelRegistryInternalService.reportHealth(id, request);

        assertThat(response.applied()).isFalse();
        verify(chatModelRepository, never()).disableIfActive(any());
        verify(modelRegistryVersionService, never()).bump();
    }

    @Test
    void inactive_activate_verifiedAtNotNull_becomesActive() {
        ChatModel model = modelWith(ChatModelStatus.INACTIVE, true);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.updateStatusIfCurrent(model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE))
                .thenReturn(1);

        ChatModelResponse response = chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE);

        assertThat(response).isNotNull();
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void inactive_activate_neverVerified_rejected409() {
        ChatModel model = modelWith(ChatModelPurpose.CHAT, ChatModelStatus.INACTIVE, true, false);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_NOT_VERIFIED);
    }

    @Test
    void disabled_activate_rejected409_mustReVerifyFirst() {
        ChatModel model = modelWith(ChatModelStatus.DISABLED, true);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
    }

    @Test
    void delete_anyStatus_becomesInactiveIsActiveFalse() {
        for (ChatModelStatus status : Set.of(
                ChatModelStatus.PENDING, ChatModelStatus.ACTIVE, ChatModelStatus.INACTIVE, ChatModelStatus.DISABLED)) {
            ChatModel model = modelWith(status, true);
            when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
            when(chatModelVerificationRepository.findByChatModelIdAndStatusIn(eq(model.getId()), any()))
                    .thenReturn(java.util.List.of());

            chatModelService.delete(model.getId());

            assertThat(model.getStatus()).isEqualTo(ChatModelStatus.INACTIVE);
            assertThat(model.getIsActive()).isFalse();
        }
    }

    @Test
    void recover_restoresIsActiveTrue_statusStaysInactive() {
        ChatModel model = modelWith(ChatModelStatus.INACTIVE, false);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        chatModelService.recover(model.getId());

        assertThat(model.getIsActive()).isTrue();
        assertThat(model.getStatus()).isEqualTo(ChatModelStatus.INACTIVE);
    }

    @Test
    void recover_activateOrDeactivate_rejectedWhileNotActive() {
        // Recover only flips isActive back to true; status stays INACTIVE and never-verified rows
        // still can't activate afterwards — recover alone doesn't grant a free pass.
        ChatModel model = modelWith(ChatModelPurpose.CHAT, ChatModelStatus.INACTIVE, false, false);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        chatModelService.recover(model.getId());

        assertThatThrownBy(() -> chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_NOT_VERIFIED);
    }

    @Test
    void concurrentTransition_compareAndSetLosesRace_returns409_rowUnchanged() {
        ChatModel model = modelWith(ChatModelStatus.INACTIVE, true);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        // Simulates another request winning the CAS first — 0 rows updated here.
        when(chatModelRepository.updateStatusIfCurrent(model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE))
                .thenReturn(0);

        assertThatThrownBy(() -> chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_STATUS_CONFLICT);
        verify(modelRegistryVersionService, never()).bump();
    }
}
