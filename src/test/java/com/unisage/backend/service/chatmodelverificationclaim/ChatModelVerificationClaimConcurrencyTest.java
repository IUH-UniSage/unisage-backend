package com.unisage.backend.service.chatmodelverificationclaim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves under real concurrency what {@code findClaimableIds}'s {@code FOR UPDATE SKIP LOCKED}
 * only guarantees on paper (plan.md "Verification lifecycle" step 2) — two callers hammering the
 * same pool of {@code QUEUED} jobs must never both walk away with the same job.
 */
@Testcontainers
@SpringBootTest
class ChatModelVerificationClaimConcurrencyTest {

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
    private ChatModelVerificationRepository chatModelVerificationRepository;
    @Autowired
    private ChatModelVerificationClaimService claimService;

    private ChatModel newChatModel() {
        ChatModel model = ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.ACTIVE)
                .revision(1)
                .candidateGeneration(1)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiBaseUrl("https://api.openai.com/v1")
                .build();
        model.setIsActive(true);
        return chatModelRepository.save(model);
    }

    @Test
    void twoConcurrentCallers_neverClaimTheSameJob() throws Exception {
        int jobCount = 40;
        List<UUID> jobIds = IntStream.range(0, jobCount)
                .mapToObj(i -> chatModelVerificationRepository.save(ChatModelVerification.builder()
                        .chatModel(newChatModel())
                        .status(ChatModelVerificationStatus.QUEUED)
                        .candidateGeneration(1)
                        .baseRevision(1)
                        .candidateApiKeyEncrypted("sk-candidate-" + i)
                        .attempt(0)
                        .maxAttempts(3)
                        .build()).getId())
                .collect(Collectors.toList());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<List<InternalVerificationClaimResponse>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    List<InternalVerificationClaimResponse> claimed = new ArrayList<>();
                    // Repeated small claims (rather than one big claim(jobCount) per thread) is what
                    // actually stresses SKIP LOCKED under concurrency — both threads keep contending
                    // for the shrinking pool until it's drained.
                    List<InternalVerificationClaimResponse> batch;
                    do {
                        batch = claimService.claim(5);
                        claimed.addAll(batch);
                    } while (!batch.isEmpty());
                    return claimed;
                }));
            }
            startLatch.countDown();

            List<UUID> allClaimedIds = new ArrayList<>();
            for (Future<List<InternalVerificationClaimResponse>> future : futures) {
                for (InternalVerificationClaimResponse response : future.get(30, TimeUnit.SECONDS)) {
                    allClaimedIds.add(response.jobId());
                }
            }

            Set<UUID> distinctClaimed = new HashSet<>(allClaimedIds);
            assertThat(allClaimedIds).as("no job claimed twice across the two callers").hasSameSizeAs(distinctClaimed);
            assertThat(distinctClaimed).as("every job eventually claimed by exactly one caller")
                    .containsExactlyInAnyOrderElementsOf(jobIds);
        } finally {
            pool.shutdown();
        }

        for (UUID jobId : jobIds) {
            ChatModelVerification job = chatModelVerificationRepository.findById(jobId).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(ChatModelVerificationStatus.RUNNING);
            assertThat(job.getAttempt()).isEqualTo(1);
        }
    }
}
