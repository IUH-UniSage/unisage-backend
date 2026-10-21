package com.unisage.backend.service.embeddingreindex;

import static com.unisage.backend.service.embeddingreindex.EmbeddingReindexServiceTest.assertError;
import static com.unisage.backend.service.embeddingreindex.EmbeddingReindexServiceTest.embeddingModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexProgressRequest;
import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexResultRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexClaimResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.EmbeddingReindexRun;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.EmbeddingReindexStatus;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.EmbeddingReindexRunRepository;
import com.unisage.backend.service.embeddingindexidentity.EmbeddingIndexIdentityService;
import com.unisage.backend.service.embeddingindexpointer.EmbeddingIndexPointerService;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;

class EmbeddingReindexInternalServiceTest {

    private static final String TARGET = "unisage_chunks__20261017093000";
    private static final Float[] FINGERPRINT = {0.9f, -0.4f, 0.2f};

    private final EmbeddingReindexRunRepository runRepository = mock(EmbeddingReindexRunRepository.class);
    private final ChatModelRepository chatModelRepository = mock(ChatModelRepository.class);
    private final EmbeddingIndexIdentityService identityService = mock(EmbeddingIndexIdentityService.class);
    private final EmbeddingIndexPointerService pointerService = mock(EmbeddingIndexPointerService.class);
    private final ModelRegistryVersionService versionService = mock(ModelRegistryVersionService.class);
    private EmbeddingReindexInternalServiceImpl service;
    private ChatModel model;
    private EmbeddingReindexRun run;
    private UUID lease;

    @BeforeEach
    void setUp() {
        service = new EmbeddingReindexInternalServiceImpl(
                runRepository, chatModelRepository, identityService, pointerService, versionService);
        model = embeddingModel("text-embedding-3-large", 3072, FINGERPRINT);
        lease = UUID.randomUUID();
        run = EmbeddingReindexRun.builder()
                .id(UUID.randomUUID())
                .baseCollection("unisage_chunks")
                .sourceCollection("unisage_chunks")
                .targetCollection(TARGET)
                .targetChatModel(model)
                .targetModelRevision(model.getRevision())
                .status(EmbeddingReindexStatus.RUNNING)
                .leaseToken(lease)
                .build();
        when(runRepository.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        when(chatModelRepository.findByIdForUpdate(model.getId())).thenReturn(Optional.of(model));
        when(chatModelRepository.updateStatusIfCurrent(model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE))
                .thenReturn(1);
        when(identityService.getIdentity(TARGET)).thenReturn(Optional.of(new InternalEmbeddingIndexIdentityResponse(
                TARGET, "openai", "text-embedding-3-large", null, null, 3072, FINGERPRINT,
                LocalDateTime.now(), "reindex")));
    }

    private InternalEmbeddingReindexResultRequest success(UUID token) {
        return new InternalEmbeddingReindexResultRequest(token, true, 10, 10, 0, null);
    }

    // ── rebuild (UNISAGE-104): the live model stays, only the pointer moves ──

    private void asRebuildOfTheLiveModel() {
        model.setStatus(ChatModelStatus.ACTIVE);
        run.setRebuild(true);
    }

    @Test
    void claimOfARebuildAcceptsTheActiveModelAndSaysSo() {
        asRebuildOfTheLiveModel();
        run.setStatus(EmbeddingReindexStatus.QUEUED);
        when(runRepository.findClaimableIds(1)).thenReturn(List.of(run.getId()));
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        var claims = service.claim(1);

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).rebuild()).isTrue();
        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.RUNNING);
    }

    @Test
    void claimOfARebuildFailsWhenTheLiveModelWasSwitchedOff() {
        asRebuildOfTheLiveModel();
        model.setStatus(ChatModelStatus.INACTIVE);
        run.setStatus(EmbeddingReindexStatus.QUEUED);
        when(runRepository.findClaimableIds(1)).thenReturn(List.of(run.getId()));
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        assertThat(service.claim(1)).isEmpty();
        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.FAILED);
    }

    @Test
    void rebuildSuccessMovesThePointerAndLeavesTheModelAlone() {
        asRebuildOfTheLiveModel();

        var status = service.result(run.getId(), success(lease));

        assertThat(status.status()).isEqualTo(EmbeddingReindexStatus.SUCCEEDED);
        verify(pointerService).pointTo(TARGET);
        verify(chatModelRepository, never()).deactivateOtherActiveEmbedding(any());
        verify(chatModelRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(versionService).bump();
    }

    @Test
    void claimHandsOutTheTargetCredentialUnderAFreshLease() {
        run.setStatus(EmbeddingReindexStatus.QUEUED);
        run.setLeaseToken(null);
        when(runRepository.findClaimableIds(anyInt())).thenReturn(List.of(run.getId()));
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        List<InternalEmbeddingReindexClaimResponse> claimed = service.claim(1);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).leaseToken()).isNotNull().isEqualTo(run.getLeaseToken());
        assertThat(claimed.get(0).targetCollection()).isEqualTo(TARGET);
        assertThat(claimed.get(0).dimension()).isEqualTo(3072);
        assertThat(claimed.get(0).credential().modelName()).isEqualTo("text-embedding-3-large");
        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    void claimFailsARunWhoseModelWasEditedMeanwhile() {
        run.setStatus(EmbeddingReindexStatus.QUEUED);
        model.setRevision(model.getRevision() + 1);
        when(runRepository.findClaimableIds(anyInt())).thenReturn(List.of(run.getId()));
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        assertThat(service.claim(1)).isEmpty();
        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.FAILED);
    }

    @Test
    void progressRenewsTheLease() {
        var response = service.progress(run.getId(), new InternalEmbeddingReindexProgressRequest(lease, 100, 40, 1));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.RUNNING);
        assertThat(run.getProcessedPoints()).isEqualTo(40);
        assertThat(run.getLeaseUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    void progressWithAStaleLeaseIsRefused() {
        assertError(() -> service.progress(run.getId(),
                new InternalEmbeddingReindexProgressRequest(UUID.randomUUID(), 100, 40, 0)),
                ErrorCode.EMBEDDING_REINDEX_LEASE_LOST);
    }

    @Test
    void progressOnACancelledRunTellsTheAgentToStop() {
        run.setStatus(EmbeddingReindexStatus.CANCELLED);

        var response = service.progress(run.getId(), new InternalEmbeddingReindexProgressRequest(lease, 100, 40, 0));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.CANCELLED);
    }

    @Test
    void successCutsOverToTheTargetCollectionAndActivatesTheModel() {
        var response = service.result(run.getId(), success(lease));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.SUCCEEDED);
        verify(pointerService).pointTo(TARGET);
        verify(chatModelRepository).deactivateOtherActiveEmbedding(model.getId());
        verify(chatModelRepository).updateStatusIfCurrent(model.getId(), ChatModelStatus.INACTIVE, ChatModelStatus.ACTIVE);
        verify(versionService).bump();
        assertThat(run.getStatus()).isEqualTo(EmbeddingReindexStatus.SUCCEEDED);
    }

    @Test
    void successWithoutATargetIdentityFailsInsteadOfCuttingOver() {
        when(identityService.getIdentity(TARGET)).thenReturn(Optional.empty());

        var response = service.result(run.getId(), success(lease));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.FAILED);
        verify(pointerService, never()).pointTo(any());
        assertThat(run.getErrorMessage()).isNotBlank();
    }

    @Test
    void successAfterTheModelWasActivatedElsewhereFails() {
        model.setStatus(ChatModelStatus.ACTIVE);

        var response = service.result(run.getId(), success(lease));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.FAILED);
        verify(pointerService, never()).pointTo(any());
    }

    @Test
    void reportedFailureKeepsTheLiveCollection() {
        var response = service.result(run.getId(),
                new InternalEmbeddingReindexResultRequest(lease, false, 10, 3, 7, "quota exceeded"));

        assertThat(response.status()).isEqualTo(EmbeddingReindexStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("quota exceeded");
        verify(pointerService, never()).pointTo(any());
        verify(versionService).bump();
    }
}
