package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findClaimableIds} is the race-safe selection half of the pull-based claim (Task 6 does the
 * actual claim write) — this test only proves the filter predicate matches plan.md "Verification
 * lifecycle" step 2: QUEUED ready for its next attempt, or RUNNING with an expired lease.
 */
@Testcontainers
@SpringBootTest
class ChatModelVerificationRepositoryTest {

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
    private ChatModelVerificationRepository verificationRepository;

    private ChatModel newChatModel() {
        return chatModelRepository.save(ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.PENDING)
                .revision(0)
                .candidateGeneration(1)
                .apiBaseUrl("https://api.openai.com/v1")
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .build());
    }

    @Test
    @Transactional
    void findClaimableIds_includesQueuedReadyAndExpiredRunning_excludesFutureAndFreshLease() {
        // A unique partial index allows at most 1 open (QUEUED/RUNNING) job per chat_model_id, so
        // each open job here needs its own row; the terminal SUCCEEDED job can share any model.
        ChatModelVerification queuedReady = verificationRepository.save(ChatModelVerification.builder()
                .chatModel(newChatModel()).status(ChatModelVerificationStatus.QUEUED)
                .candidateGeneration(1).baseRevision(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(1))
                .build());
        ChatModelVerification queuedFuture = verificationRepository.save(ChatModelVerification.builder()
                .chatModel(newChatModel()).status(ChatModelVerificationStatus.QUEUED)
                .candidateGeneration(1).baseRevision(0)
                .nextAttemptAt(LocalDateTime.now().plusMinutes(5))
                .build());
        ChatModelVerification runningExpired = verificationRepository.save(ChatModelVerification.builder()
                .chatModel(newChatModel()).status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(1).baseRevision(0)
                .leaseUntil(LocalDateTime.now().minusSeconds(1))
                .leaseToken(UUID.randomUUID())
                .build());
        ChatModelVerification runningFresh = verificationRepository.save(ChatModelVerification.builder()
                .chatModel(newChatModel()).status(ChatModelVerificationStatus.RUNNING)
                .candidateGeneration(1).baseRevision(0)
                .leaseUntil(LocalDateTime.now().plusSeconds(60))
                .leaseToken(UUID.randomUUID())
                .build());
        ChatModelVerification succeeded = verificationRepository.save(ChatModelVerification.builder()
                .chatModel(newChatModel()).status(ChatModelVerificationStatus.SUCCEEDED)
                .candidateGeneration(1).baseRevision(0)
                .build());

        List<UUID> claimable = verificationRepository.findClaimableIds(10);

        assertThat(claimable)
                .contains(queuedReady.getId(), runningExpired.getId())
                .doesNotContain(queuedFuture.getId(), runningFresh.getId(), succeeded.getId());
    }

    @Test
    @Transactional
    void findClaimableIds_respectsLimit() {
        for (int i = 0; i < 3; i++) {
            verificationRepository.save(ChatModelVerification.builder()
                    .chatModel(newChatModel()).status(ChatModelVerificationStatus.QUEUED)
                    .candidateGeneration(1).baseRevision(0)
                    .build());
        }

        assertThat(verificationRepository.findClaimableIds(2)).hasSize(2);
    }
}
