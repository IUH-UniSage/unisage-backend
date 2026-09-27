package com.unisage.backend.service.chatmodel;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.event.consumer.ModelRegistryEventPublisher;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 7 audit — proves, against a real Postgres transaction (not mocks), that the
 * ChatModelServiceImpl write paths that must bump the registry version actually do (exactly once,
 * publisher fires exactly once), and that a rolled-back transaction bumps/publishes nothing. The
 * bump()/publish-after-commit *mechanism* itself is already proven generically by
 * ModelRegistryVersionServiceTest; this proves individual call sites correctly participate in it.
 */
@Testcontainers
@SpringBootTest
@Import(ChatModelWritePathVersionBumpTest.TestTransactionalCaller.class)
class ChatModelWritePathVersionBumpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    private ChatModelRepository chatModelRepository;
    @Autowired
    private ChatModelService chatModelService;
    @Autowired
    private ModelRegistryVersionService modelRegistryVersionService;
    @Autowired
    private TestTransactionalCaller testCaller;

    @MockitoSpyBean
    private ModelRegistryEventPublisher modelRegistryEventPublisher;

    private int publishCount() {
        return Mockito.mockingDetails(modelRegistryEventPublisher).getInvocations().size();
    }

    private ChatModel activeRow() {
        ChatModel model = ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.ACTIVE)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .revision(1)
                .candidateGeneration(1)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKeyEncrypted("sk-old")
                .apiBaseUrl("https://api.openai.com/v1")
                .priority(1)
                .verifiedAt(LocalDateTime.now())
                .build();
        model.setIsActive(true);
        return chatModelRepository.save(model);
    }

    private ChatModel inactiveVerifiedRow() {
        ChatModel model = ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.INACTIVE)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .revision(1)
                .candidateGeneration(1)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKeyEncrypted("sk-old")
                .apiBaseUrl("https://api.openai.com/v1")
                .priority(1)
                .verifiedAt(LocalDateTime.now())
                .build();
        model.setIsActive(true);
        return chatModelRepository.save(model);
    }

    // ── updatePriority ───────────────────────────────────────────────────

    @Test
    void updatePriority_commits_bumpsExactlyOnce_publishesExactlyOnce() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        chatModelService.updatePriority(model.getId(), 5);

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before + 1);
        assertThat(publishCount() - publishBefore).isEqualTo(1);
    }

    @Test
    void updatePriority_rolledBack_bumpsNothing_publishesNothing() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        assertThatThrownBy(() -> testCaller.updatePriorityThenRollback(model.getId(), 5))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced rollback");

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before);
        assertThat(publishCount() - publishBefore).isEqualTo(0);
        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getPriority()).isEqualTo(1);
    }

    // ── updateStatus: deactivate (ACTIVE -> INACTIVE) ────────────────────

    @Test
    void deactivate_commits_bumpsExactlyOnce_publishesExactlyOnce() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        chatModelService.updateStatus(model.getId(), ChatModelStatus.INACTIVE);

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before + 1);
        assertThat(publishCount() - publishBefore).isEqualTo(1);
    }

    @Test
    void deactivate_rolledBack_bumpsNothing_publishesNothing() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        assertThatThrownBy(() -> testCaller.updateStatusThenRollback(model.getId(), ChatModelStatus.INACTIVE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced rollback");

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before);
        assertThat(publishCount() - publishBefore).isEqualTo(0);
        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getStatus())
                .isEqualTo(ChatModelStatus.ACTIVE);
    }

    // ── updateStatus: activate (INACTIVE -> ACTIVE, non-embedding) ──────

    @Test
    void activate_commits_bumpsExactlyOnce_publishesExactlyOnce() {
        ChatModel model = inactiveVerifiedRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        chatModelService.updateStatus(model.getId(), ChatModelStatus.ACTIVE);

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before + 1);
        assertThat(publishCount() - publishBefore).isEqualTo(1);
    }

    @Test
    void activate_rolledBack_bumpsNothing_publishesNothing() {
        ChatModel model = inactiveVerifiedRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        assertThatThrownBy(() -> testCaller.updateStatusThenRollback(model.getId(), ChatModelStatus.ACTIVE))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced rollback");

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before);
        assertThat(publishCount() - publishBefore).isEqualTo(0);
        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getStatus())
                .isEqualTo(ChatModelStatus.INACTIVE);
    }

    // ── delete of an ACTIVE row ───────────────────────────────────────────

    @Test
    void deleteActiveRow_commits_bumpsExactlyOnce_publishesExactlyOnce() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        chatModelService.delete(model.getId());

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before + 1);
        assertThat(publishCount() - publishBefore).isEqualTo(1);
    }

    @Test
    void deleteActiveRow_rolledBack_bumpsNothing_publishesNothing() {
        ChatModel model = activeRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        assertThatThrownBy(() -> testCaller.deleteThenRollback(model.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced rollback");

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before);
        assertThat(publishCount() - publishBefore).isEqualTo(0);
        ChatModel unchanged = chatModelRepository.findById(model.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        assertThat(unchanged.getIsActive()).isTrue();
    }

    @Test
    void deleteNonActiveRow_commits_neverBumps() {
        ChatModel model = inactiveVerifiedRow();
        long before = modelRegistryVersionService.currentVersion();
        int publishBefore = publishCount();

        chatModelService.delete(model.getId());

        assertThat(modelRegistryVersionService.currentVersion()).isEqualTo(before);
        assertThat(publishCount() - publishBefore).isEqualTo(0);
    }

    /**
     * Joins the caller's real service call in an outer transaction, then force-fails so the whole
     * unit — including any bump()/save() the service call made — rolls back together (same pattern
     * as {@code ModelRegistryVersionServiceTest.TestTransactionalCaller}).
     */
    @Service
    static class TestTransactionalCaller {

        private final ChatModelService chatModelService;

        TestTransactionalCaller(ChatModelService chatModelService) {
            this.chatModelService = chatModelService;
        }

        @Transactional
        public void updatePriorityThenRollback(java.util.UUID id, Integer priority) {
            chatModelService.updatePriority(id, priority);
            throw new RuntimeException("forced rollback after updatePriority");
        }

        @Transactional
        public void updateStatusThenRollback(java.util.UUID id, ChatModelStatus targetStatus) {
            chatModelService.updateStatus(id, targetStatus);
            throw new RuntimeException("forced rollback after updateStatus");
        }

        @Transactional
        public void deleteThenRollback(java.util.UUID id) {
            chatModelService.delete(id);
            throw new RuntimeException("forced rollback after delete");
        }
    }
}
