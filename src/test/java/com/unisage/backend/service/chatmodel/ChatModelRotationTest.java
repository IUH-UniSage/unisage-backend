package com.unisage.backend.service.chatmodel;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.request.ChatModelUpdateRequest;
import com.unisage.backend.dto.request.internal.InternalVerificationResultRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.internal.InternalVerificationResultResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.entity.enums.VerificationResultType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.service.modelregistry.ChatModelCandidatePromotionService;
import com.unisage.backend.service.modelregistry.ChatModelCandidatePromotionServiceImpl;
import com.unisage.backend.service.modelregistry.ChatModelVerificationResultService;
import com.unisage.backend.service.modelregistry.ChatModelVerificationResultServiceImpl;
import com.unisage.backend.service.modelregistry.EmbeddingIndexIdentityService;
import com.unisage.backend.service.modelregistry.ModelRegistryVersionService;
import com.unisage.backend.utils.SsrfGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staged credential rotation from plan.md "Credential rotation". Every scenario is enabled,
 * including {@code activeRow_verifyFail_oldKeyStillRuns_rowUnchanged}, which needed the result
 * endpoint's service (Task 6) to actually report a verify failure.
 */
class ChatModelRotationTest {

    private ChatModelRepository chatModelRepository;
    private ChatModelVerificationRepository chatModelVerificationRepository;
    private ModelRegistryVersionService modelRegistryVersionService;
    private ChatModelServiceImpl chatModelService;
    private ChatModelCandidatePromotionService promotionService;
    private ChatModelVerificationResultService resultService;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelVerificationRepository = mock(ChatModelVerificationRepository.class);
        modelRegistryVersionService = mock(ModelRegistryVersionService.class);
        chatModelService = new ChatModelServiceImpl(
                chatModelRepository, chatModelVerificationRepository, new SsrfGuard(),
                mock(EmbeddingIndexIdentityService.class), modelRegistryVersionService);
        promotionService = new ChatModelCandidatePromotionServiceImpl(
                chatModelRepository, chatModelVerificationRepository,
                mock(EmbeddingIndexIdentityService.class), modelRegistryVersionService);
        resultService = new ChatModelVerificationResultServiceImpl(
                chatModelRepository, chatModelVerificationRepository, promotionService);
        ReflectionTestUtils.setField(chatModelService, "embeddingCollectionName", "unisage_chunks");
        ReflectionTestUtils.setField(promotionService, "embeddingCollectionName", "unisage_chunks");

        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatModelVerificationRepository.save(any(ChatModelVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ChatModel activeCloudRow() {
        ChatModel model = ChatModel.builder()
                .id(UUID.randomUUID())
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.ACTIVE)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKeyEncrypted("sk-old")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .priority(1)
                .revision(1)
                .candidateGeneration(1)
                .verifiedAt(LocalDateTime.now())
                .build();
        model.setIsActive(true);
        return model;
    }

    private ChatModelUpdateRequest.ChatModelUpdateRequestBuilder baseUpdateFrom(ChatModel model) {
        return ChatModelUpdateRequest.builder()
                .sourceType(model.getSourceType())
                .llmProvider(model.getLlmProvider())
                .llmModelName(model.getLlmModelName())
                .modelSourceRef(model.getModelSourceRef())
                .apiBaseUrl(model.getApiBaseUrl())
                .maxRpm(model.getMaxRpm())
                .priority(model.getPriority());
    }

    @Test
    void activeRow_editApiKey_snapshotStillServesOldKeyUntilVerified() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).apiKey(JsonNullable.of("sk-new")).build();
        chatModelService.update(model.getId(), request);

        // The row itself keeps serving the old key until the candidate is promoted.
        assertThat(model.getApiKeyEncrypted()).isEqualTo("sk-old");
        assertThat(model.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        verify(chatModelVerificationRepository).save(argThat(job ->
                job.getCandidateApiKeyEncrypted().equals("sk-new") && job.getStatus() == ChatModelVerificationStatus.QUEUED));
    }

    @Test
    void activeRow_verifyFail_oldKeyStillRuns_rowUnchanged() {
        ChatModel model = activeCloudRow();
        UUID jobId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        ChatModelVerification job = ChatModelVerification.builder()
                .id(jobId)
                .chatModel(model)
                .status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(model.getCandidateGeneration())
                .baseRevision(model.getRevision())
                .candidateApiKeyEncrypted("sk-new")
                .leaseToken(leaseToken)
                .attempt(1)
                .maxAttempts(3)
                .build();
        when(chatModelVerificationRepository.findChatModelIdById(jobId)).thenReturn(Optional.of(model.getId()));
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));
        when(chatModelVerificationRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        when(chatModelVerificationRepository.isLeaseCurrentlyValid(jobId, leaseToken)).thenReturn(true);

        InternalVerificationResultRequest request = new InternalVerificationResultRequest(
                leaseToken, VerificationResultType.PERMANENT, "invalid_api_key", "key revoked", null, null);
        InternalVerificationResultResponse response = resultService.submitResult(jobId, request);

        assertThat(response.applied()).isTrue();
        assertThat(response.duplicate()).isFalse();
        assertThat(job.getStatus()).isEqualTo(ChatModelVerificationStatus.FAILED);
        // The old key keeps serving — a failed verify never touches the row.
        assertThat(model.getApiKeyEncrypted()).isEqualTo("sk-old");
        assertThat(model.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        verify(modelRegistryVersionService, never()).bump();
    }

    @Test
    void update_apiKeyFieldAbsent_keepsOldKey() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).build(); // apiKey defaults to undefined()
        chatModelService.update(model.getId(), request);

        assertThat(model.getApiKeyEncrypted()).isEqualTo("sk-old");
        verify(chatModelVerificationRepository, never()).save(any());
    }

    @Test
    void update_apiKeyNull_keepsOldKey() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).apiKey(JsonNullable.of(null)).build();
        chatModelService.update(model.getId(), request);

        assertThat(model.getApiKeyEncrypted()).isEqualTo("sk-old");
        verify(chatModelVerificationRepository, never()).save(any());
    }

    @Test
    void update_apiKeyEmptyOrBlank_400() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        for (String blank : List.of("", "   ")) {
            ChatModelUpdateRequest request = baseUpdateFrom(model).apiKey(JsonNullable.of(blank)).build();
            assertThatThrownBy(() -> chatModelService.update(model.getId(), request))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED);
        }
    }

    @Test
    void update_apiKeyNewValue_becomesCandidate_notWrittenYet() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).apiKey(JsonNullable.of("sk-new")).build();
        ChatModelResponse response = chatModelService.update(model.getId(), request);

        assertThat(response.hasApiKey()).isTrue();
        assertThat(model.getApiKeyEncrypted()).isEqualTo("sk-old");
        assertThat(model.getCandidateGeneration()).isEqualTo(2);
    }

    @Test
    void update_apiBaseUrlHostChanged_withoutNewApiKey_400() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).apiBaseUrl("https://api.anthropic.com/v1").build();

        assertThatThrownBy(() -> chatModelService.update(model.getId(), request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED_FOR_NEW_HOST);
    }

    @Test
    void update_apiBaseUrlHostChanged_withNewApiKey_ok() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model)
                .apiBaseUrl("https://api.anthropic.com/v1")
                .llmProvider("anthropic")
                .apiKey(JsonNullable.of("sk-ant-new"))
                .build();

        ChatModelResponse response = chatModelService.update(model.getId(), request);

        assertThat(response).isNotNull();
        assertThat(model.getApiBaseUrl()).isEqualTo("https://api.openai.com/v1"); // row untouched — staged
        verify(chatModelVerificationRepository).save(argThat(job ->
                "https://api.anthropic.com/v1".equals(job.getCandidateApiBaseUrl())));
    }

    @Test
    void clearApiKey_onlyValidForSelfHosted() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest cloudClear = baseUpdateFrom(model).clearApiKey(true).build();
        assertThatThrownBy(() -> chatModelService.update(model.getId(), cloudClear))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        ChatModel selfHosted = activeCloudRow();
        selfHosted.setSourceType(ChatModelSourceType.SELF_HOSTED);
        when(chatModelRepository.findByIdForUpdate(selfHosted.getId())).thenReturn(Optional.of(selfHosted));
        ChatModelUpdateRequest selfHostedClear = baseUpdateFrom(selfHosted)
                .sourceType(ChatModelSourceType.SELF_HOSTED)
                .clearApiKey(true)
                .build();

        chatModelService.update(selfHosted.getId(), selfHostedClear);
        verify(chatModelVerificationRepository).save(argThat(job -> job.getCandidateApiKeyEncrypted() == null));
    }

    @Test
    void create_startsAtRevisionZero_candidateGenerationOne() {
        ChatModelRequest request = ChatModelRequest.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKey("sk-abc")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .build();
        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(invocation -> {
            ChatModel entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });

        ChatModelResponse response = chatModelService.create(request);

        assertThat(response.revision()).isEqualTo(0);
        verify(chatModelVerificationRepository).save(argThat(job ->
                job.getCandidateGeneration() == 1 && job.getBaseRevision() == 0
                && job.getStatus() == ChatModelVerificationStatus.QUEUED));
    }

    @Test
    void verifyOk_copiesCandidateIntoRow_revisionIncrements_verifiedAtSet() {
        ChatModel model = activeCloudRow();
        ChatModelVerification job = ChatModelVerification.builder()
                .id(UUID.randomUUID())
                .chatModel(model)
                .status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(model.getCandidateGeneration())
                .baseRevision(model.getRevision())
                .candidateLlmProvider(model.getLlmProvider())
                .candidateLlmModelName(model.getLlmModelName())
                .candidateModelSourceRef(model.getModelSourceRef())
                .candidateApiKeyEncrypted("sk-new")
                .candidateApiBaseUrl(model.getApiBaseUrl())
                .attempt(1)
                .maxAttempts(3)
                .build();
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.promoteCandidate(eq(model.getId()), any(), any(), any(), eq("sk-new"), any(),
                any(), (Float[]) any(), eq(ChatModelStatus.ACTIVE), eq(job.getCandidateGeneration()), eq(job.getBaseRevision())))
                .thenReturn(1);

        ChatModelCandidatePromotionService.Outcome outcome = promotionService.promote(job);

        assertThat(outcome).isEqualTo(ChatModelCandidatePromotionService.Outcome.PROMOTED);
        assertThat(job.getStatus()).isEqualTo(ChatModelVerificationStatus.SUCCEEDED);
        verify(modelRegistryVersionService).bump();
    }

    @Test
    void editWhileCandidatePending_bumpsCandidateGeneration_oldJobSuperseded() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest first = baseUpdateFrom(model).apiKey(JsonNullable.of("sk-first")).build();
        chatModelService.update(model.getId(), first);
        assertThat(model.getCandidateGeneration()).isEqualTo(2);

        ChatModelUpdateRequest second = baseUpdateFrom(model).apiKey(JsonNullable.of("sk-second")).build();
        chatModelService.update(model.getId(), second);
        assertThat(model.getCandidateGeneration()).isEqualTo(3);

        verify(chatModelVerificationRepository, times(2)).supersedeOpenJobs(model.getId());
    }

    @Test
    void priorityAndMaxRpmEdits_applyImmediately_noVerifyNeeded() {
        ChatModel model = activeCloudRow();
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));

        ChatModelUpdateRequest request = baseUpdateFrom(model).priority(5).maxRpm(120).build();
        chatModelService.update(model.getId(), request);

        assertThat(model.getPriority()).isEqualTo(5);
        assertThat(model.getMaxRpm()).isEqualTo(120);
        verify(chatModelVerificationRepository, never()).save(any());
        verify(modelRegistryVersionService).bump();
    }
}
