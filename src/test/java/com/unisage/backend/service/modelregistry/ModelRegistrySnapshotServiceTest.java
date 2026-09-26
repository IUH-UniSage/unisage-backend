package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistrySnapshotResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistrySnapshotResponse.CredentialEntry;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.repository.ChatModelRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * plan.md "Internal API contract" endpoint #1 — {@code GET /internal/model-registry/snapshot}.
 * The repository query itself (`findAllActiveForSnapshot`, only `is_active AND status = ACTIVE`) is
 * exercised here purely via mocked return values; the "only active rows in the query" guarantee is
 * the repository's own responsibility (a plain JPQL WHERE clause), so what this test proves is that
 * the service groups/serializes correctly and never substitutes a row's staged candidate values.
 */
class ModelRegistrySnapshotServiceTest {

    private ChatModelRepository chatModelRepository;
    private ModelRegistryVersionService modelRegistryVersionService;
    private EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private ModelRegistryInternalServiceImpl service;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        modelRegistryVersionService = mock(ModelRegistryVersionService.class);
        embeddingIndexIdentityService = mock(EmbeddingIndexIdentityService.class);
        service = new ModelRegistryInternalServiceImpl(
                chatModelRepository, modelRegistryVersionService, embeddingIndexIdentityService);
        ReflectionTestUtils.setField(service, "embeddingCollectionName", "unisage_chunks");
    }

    private ChatModel activeRow(ChatModelPurpose purpose, int priority, String apiKey) {
        ChatModel model = ChatModel.builder()
                .id(UUID.randomUUID())
                .modelPurpose(purpose)
                .status(ChatModelStatus.ACTIVE)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKeyEncrypted(apiKey)
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .priority(priority)
                .revision(3)
                .candidateGeneration(3)
                .verifiedAt(LocalDateTime.now())
                .build();
        model.setIsActive(true);
        return model;
    }

    @Test
    void snapshot_groupsByPurpose_sortedByPriority_readsVersionAndDataTogether() {
        ChatModel chat2 = activeRow(ChatModelPurpose.CHAT, 2, "sk-chat-2");
        ChatModel chat1 = activeRow(ChatModelPurpose.CHAT, 1, "sk-chat-1");
        ChatModel embedding = activeRow(ChatModelPurpose.EMBEDDING, 1, "sk-embed");
        // Repository is the one responsible for ordering; mock returns already-ordered exactly as
        // the real query would (modelPurpose ASC, priority ASC).
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of(chat1, chat2, embedding));
        when(modelRegistryVersionService.currentVersion()).thenReturn(42L);
        when(embeddingIndexIdentityService.getIdentity("unisage_chunks")).thenReturn(Optional.empty());

        InternalModelRegistrySnapshotResponse snapshot = service.getSnapshot();

        assertThat(snapshot.version()).isEqualTo(42L);
        assertThat(snapshot.purposes().get(ChatModelPurpose.CHAT)).extracting(CredentialEntry::apiKey)
                .containsExactly("sk-chat-1", "sk-chat-2");
        assertThat(snapshot.purposes().get(ChatModelPurpose.EMBEDDING)).extracting(CredentialEntry::apiKey)
                .containsExactly("sk-embed");
        assertThat(snapshot.purposes().get(ChatModelPurpose.EXTRACTION)).isEmpty();
        assertThat(snapshot.embeddingIndexIdentity()).isNull();
    }

    @Test
    void snapshot_includesEmbeddingIndexIdentity_whenEstablished() {
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of());
        when(modelRegistryVersionService.currentVersion()).thenReturn(1L);
        InternalEmbeddingIndexIdentityResponse identity = new InternalEmbeddingIndexIdentityResponse(
                "unisage_chunks", "openai", "text-embedding-3-small", null,
                "https://api.openai.com/v1", 1536, new Float[] {0.1f}, LocalDateTime.now(), "first-upsert");
        when(embeddingIndexIdentityService.getIdentity("unisage_chunks")).thenReturn(Optional.of(identity));

        InternalModelRegistrySnapshotResponse snapshot = service.getSnapshot();

        assertThat(snapshot.embeddingIndexIdentity()).isEqualTo(identity);
    }

    @Test
    void snapshot_credentialEntry_carriesRevision() {
        ChatModel model = activeRow(ChatModelPurpose.CHAT, 1, "sk-chat-1");
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of(model));
        when(modelRegistryVersionService.currentVersion()).thenReturn(7L);
        when(embeddingIndexIdentityService.getIdentity(any())).thenReturn(Optional.empty());

        InternalModelRegistrySnapshotResponse snapshot = service.getSnapshot();

        CredentialEntry entry = snapshot.purposes().get(ChatModelPurpose.CHAT).get(0);
        assertThat(entry.revision()).isEqualTo(3);
        assertThat(entry.id()).isEqualTo(model.getId());
        assertThat(entry.provider()).isEqualTo("openai");
        assertThat(entry.modelName()).isEqualTo("gpt-4o-mini");
        assertThat(entry.apiBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(entry.priority()).isEqualTo(1);
        assertThat(entry.maxRpm()).isEqualTo(60);
        assertThat(entry.sourceType()).isEqualTo(ChatModelSourceType.CLOUD_API);
    }

    /**
     * Acceptance criterion #7: a row with a pending (unverified) rotation candidate must still
     * report its OLD, currently-active values — the row's own columns are what
     * {@code findAllActiveForSnapshot} returns; a pending candidate lives entirely in a separate
     * {@code ChatModelVerification} row that this service never touches, so this is proven simply
     * by never having promoted the row (its own fields are still the pre-rotation values) and
     * asserting the snapshot reflects exactly that — no candidate data path exists to leak from.
     */
    @Test
    void rowWithPendingRotationCandidate_snapshotReportsOldValues_notCandidate() {
        ChatModel model = activeRow(ChatModelPurpose.CHAT, 1, "sk-old-key");
        // Simulate: SA has edited this row (candidate_generation bumped by ChatModelServiceImpl.update),
        // but the candidate lives only in ChatModelVerification and has NOT been promoted — the row's
        // own apiKeyEncrypted/apiBaseUrl/llmModelName are untouched by that edit.
        model.setCandidateGeneration(model.getCandidateGeneration() + 1);

        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of(model));
        when(modelRegistryVersionService.currentVersion()).thenReturn(5L);
        when(embeddingIndexIdentityService.getIdentity(any())).thenReturn(Optional.empty());

        InternalModelRegistrySnapshotResponse snapshot = service.getSnapshot();

        CredentialEntry entry = snapshot.purposes().get(ChatModelPurpose.CHAT).get(0);
        assertThat(entry.apiKey()).isEqualTo("sk-old-key");
        assertThat(entry.apiBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(entry.modelName()).isEqualTo("gpt-4o-mini");
        // Revision only bumps on promotion, so it stays at the pre-edit value too.
        assertThat(entry.revision()).isEqualTo(3);
    }

    @Test
    void toString_redactsApiKey() {
        ChatModel model = activeRow(ChatModelPurpose.CHAT, 1, "sk-super-secret-value");
        when(chatModelRepository.findAllActiveForSnapshot()).thenReturn(List.of(model));
        when(modelRegistryVersionService.currentVersion()).thenReturn(1L);
        when(embeddingIndexIdentityService.getIdentity(any())).thenReturn(Optional.empty());

        InternalModelRegistrySnapshotResponse snapshot = service.getSnapshot();

        assertThat(snapshot.toString()).doesNotContain("sk-super-secret-value");
        CredentialEntry entry = snapshot.purposes().get(ChatModelPurpose.CHAT).get(0);
        assertThat(entry.toString()).doesNotContain("sk-super-secret-value").contains("REDACTED");
    }
}
