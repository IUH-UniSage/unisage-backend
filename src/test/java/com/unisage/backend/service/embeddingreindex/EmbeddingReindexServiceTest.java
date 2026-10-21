package com.unisage.backend.service.embeddingreindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.unisage.backend.dto.request.EmbeddingReindexRequest;
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
import com.unisage.backend.utils.SecurityUtil;

class EmbeddingReindexServiceTest {

    private static final String BASE = "unisage_chunks";
    private static final Float[] OLD_FINGERPRINT = {0.1f, 0.2f, 0.3f};
    private static final Float[] NEW_FINGERPRINT = {0.9f, -0.4f, 0.2f};

    private final EmbeddingReindexRunRepository runRepository = mock(EmbeddingReindexRunRepository.class);
    private final ChatModelRepository chatModelRepository = mock(ChatModelRepository.class);
    private final EmbeddingIndexPointerService pointerService = mock(EmbeddingIndexPointerService.class);
    private final ModelRegistryVersionService versionService = mock(ModelRegistryVersionService.class);
    private EmbeddingReindexServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmbeddingReindexServiceImpl(
                runRepository, chatModelRepository, mock(UserRepository.class), pointerService,
                versionService, mock(SecurityUtil.class));
        when(pointerService.baseCollection()).thenReturn(BASE);
        when(pointerService.activeCollection()).thenReturn(BASE);
        when(pointerService.activeIdentity()).thenReturn(Optional.of(identity("text-embedding-3-small", 1536, OLD_FINGERPRINT)));
        when(runRepository.saveAndFlush(any(EmbeddingReindexRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    static InternalEmbeddingIndexIdentityResponse identity(String modelName, int dimension, Float[] fingerprint) {
        return new InternalEmbeddingIndexIdentityResponse(
                BASE, "openai", modelName, null, null, dimension, fingerprint, LocalDateTime.now(), "first-upsert");
    }

    static ChatModel embeddingModel(String modelName, int dimension, Float[] fingerprint) {
        ChatModel model = new ChatModel();
        model.setId(UUID.randomUUID());
        model.setModelPurpose(ChatModelPurpose.EMBEDDING);
        model.setStatus(ChatModelStatus.INACTIVE);
        model.setIsActive(true);
        model.setRevision(3);
        model.setVerifiedAt(LocalDateTime.now());
        model.setLlmProvider("openai");
        model.setLlmModelName(modelName);
        model.setEmbeddingDimension(dimension);
        model.setEmbeddingFingerprint(fingerprint);
        return model;
    }

    private ChatModel stored(ChatModel model) {
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));
        return model;
    }

    @Test
    void startQueuesARunIntoANewCollectionOfTheBase() {
        ChatModel model = stored(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT));

        EmbeddingReindexRunResponse run = service.start(new EmbeddingReindexRequest(model.getId(), false));

        assertThat(run.status()).isEqualTo(EmbeddingReindexStatus.QUEUED);
        assertThat(run.sourceCollection()).isEqualTo(BASE);
        assertThat(run.targetCollection()).matches(BASE + "__\\d{14}");
        assertThat(run.targetModel().embeddingDimension()).isEqualTo(3072);
        ArgumentCaptor<EmbeddingReindexRun> saved = ArgumentCaptor.forClass(EmbeddingReindexRun.class);
        verify(runRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTargetModelRevision()).isEqualTo(3);
        verify(versionService).bump();
    }

    @Test
    void startRefusesAModelWithTheSameIdentity() {
        ChatModel model = stored(embeddingModel("text-embedding-3-small", 1536, OLD_FINGERPRINT));
        assertError(() -> service.start(new EmbeddingReindexRequest(model.getId(), false)), ErrorCode.EMBEDDING_REINDEX_NOT_NEEDED);
        verify(runRepository, never()).saveAndFlush(any());
    }

    // ── rebuild (UNISAGE-104: same live model, adds the bm25 sparse vector) ──

    private ChatModel liveModel() {
        ChatModel live = stored(embeddingModel("text-embedding-3-small", 1536, OLD_FINGERPRINT));
        live.setStatus(ChatModelStatus.ACTIVE);
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of(live));
        return live;
    }

    @Test
    void rebuildQueuesARunOnTheLiveModelDespiteTheSameIdentity() {
        ChatModel live = liveModel();

        EmbeddingReindexRunResponse run = service.start(new EmbeddingReindexRequest(null, true));

        assertThat(run.status()).isEqualTo(EmbeddingReindexStatus.QUEUED);
        assertThat(run.rebuild()).isTrue();
        assertThat(run.targetModel().id()).isEqualTo(live.getId());
        assertThat(run.targetCollection()).matches(BASE + "__\\d{14}");
        ArgumentCaptor<EmbeddingReindexRun> saved = ArgumentCaptor.forClass(EmbeddingReindexRun.class);
        verify(runRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isRebuild()).isTrue();
        verify(versionService).bump();
    }

    @Test
    void rebuildIgnoresTheRequestedModelAndUsesTheLiveOne() {
        ChatModel live = liveModel();
        ChatModel other = stored(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT));

        EmbeddingReindexRunResponse run = service.start(new EmbeddingReindexRequest(other.getId(), true));

        assertThat(run.targetModel().id()).isEqualTo(live.getId());
    }

    @Test
    void rebuildRefusesWhenNothingIsIndexedOrTheLiveModelDriftedFromTheIndex() {
        liveModel();
        when(pointerService.activeIdentity()).thenReturn(Optional.empty());
        assertError(() -> service.start(new EmbeddingReindexRequest(null, true)), ErrorCode.EMBEDDING_REINDEX_NOT_NEEDED);

        when(pointerService.activeIdentity()).thenReturn(Optional.of(identity("text-embedding-3-large", 3072, NEW_FINGERPRINT)));
        assertError(() -> service.start(new EmbeddingReindexRequest(null, true)), ErrorCode.EMBEDDING_REINDEX_MODEL_INVALID);
        verify(runRepository, never()).saveAndFlush(any());
    }

    @Test
    void rebuildRefusesWithoutAnActiveEmbeddingModel() {
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of());
        assertError(() -> service.start(new EmbeddingReindexRequest(null, true)), ErrorCode.EMBEDDING_REINDEX_MODEL_INVALID);
    }

    @Test
    void aPlainStartWithoutAModelIsAValidationError() {
        assertError(() -> service.start(new EmbeddingReindexRequest(null, false)), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void startRefusesAnUnverifiedOrActiveModel() {
        ChatModel unverified = stored(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT));
        unverified.setVerifiedAt(null);
        ChatModel active = stored(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT));
        active.setStatus(ChatModelStatus.ACTIVE);
        ChatModel chat = stored(embeddingModel("gpt", 3072, NEW_FINGERPRINT));
        chat.setModelPurpose(ChatModelPurpose.CHAT);

        for (ChatModel model : new ChatModel[] {unverified, active, chat}) {
            assertError(() -> service.start(new EmbeddingReindexRequest(model.getId(), false)),
                    ErrorCode.EMBEDDING_REINDEX_MODEL_INVALID);
        }
    }

    @Test
    void startRefusesWhileAnotherRunIsOpen() {
        ChatModel model = stored(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT));
        when(runRepository.findFirstByBaseCollectionAndStatusInOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.of(new EmbeddingReindexRun()));

        assertError(() -> service.start(new EmbeddingReindexRequest(model.getId(), false)), ErrorCode.EMBEDDING_REINDEX_IN_PROGRESS);
    }

    @Test
    void cancelKeepsTheLeaseSoTheAgentIsToldCancelled() {
        UUID lease = UUID.randomUUID();
        EmbeddingReindexRun run = EmbeddingReindexRun.builder()
                .id(UUID.randomUUID())
                .status(EmbeddingReindexStatus.RUNNING)
                .leaseToken(lease)
                .targetChatModel(embeddingModel("text-embedding-3-large", 3072, NEW_FINGERPRINT))
                .build();
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));

        service.cancel(run.getId());

        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.CANCELLED);
        assertThat(run.getLeaseToken()).isEqualTo(lease);
        assertThat(run.getFinishedAt()).isNotNull();
        verify(versionService).bump();
    }

    @Test
    void cancelRefusesAFinishedRun() {
        EmbeddingReindexRun run = EmbeddingReindexRun.builder()
                .id(UUID.randomUUID()).status(EmbeddingReindexStatus.SUCCEEDED).build();
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));

        assertError(() -> service.cancel(run.getId()), ErrorCode.EMBEDDING_REINDEX_RUN_NOT_OPEN);
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(code);
    }
}
