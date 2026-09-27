package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.event.consumer.ModelRegistryEventPublisher;
import com.unisage.backend.service.chatmodelcandidatepromotion.ChatModelCandidatePromotionService;
import com.unisage.backend.service.chatmodelverificationclaim.ChatModelVerificationClaimService;
import com.unisage.backend.service.chatmodelverificationresult.ChatModelVerificationResultService;
import com.unisage.backend.service.modelregistryinternal.ModelRegistryInternalService;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.request.internal.InternalVerificationResultRequest;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse;
import com.unisage.backend.dto.response.internal.InternalVerificationResultResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.entity.enums.CredentialHealthErrorType;
import com.unisage.backend.entity.enums.VerificationResultType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Fencing token, idempotency, and rotation-race scenarios from plan.md "Verification lifecycle"
 * and "Credential rotation" — now exercised against the real claim/result endpoints (Task 6) with
 * a real Postgres (Testcontainers), so lock ordering and the DB-clock lease check are proven under
 * real transactions, not mocks.
 */
@Testcontainers
@SpringBootTest
class VerificationFencingTest {

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
    @Autowired
    private ChatModelVerificationResultService resultService;
    @Autowired
    private ModelRegistryVersionService modelRegistryVersionService;
    @Autowired
    private ModelRegistryInternalService modelRegistryInternalService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private ModelRegistryEventPublisher modelRegistryEventPublisher;

    // ── fixtures ─────────────────────────────────────────────────────────

    private ChatModel activeModel() {
        ChatModel model = ChatModel.builder()
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(ChatModelStatus.ACTIVE)
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

    private ChatModelVerification queuedJob(ChatModel model) {
        return chatModelVerificationRepository.save(ChatModelVerification.builder()
                .chatModel(model)
                .status(ChatModelVerificationStatus.QUEUED)
                .candidateGeneration(model.getCandidateGeneration())
                .baseRevision(model.getRevision())
                .candidateLlmProvider(model.getLlmProvider())
                .candidateLlmModelName(model.getLlmModelName())
                .candidateApiKeyEncrypted("sk-new")
                .candidateApiBaseUrl(model.getApiBaseUrl())
                .attempt(0)
                .maxAttempts(3)
                .build());
    }

    private InternalVerificationClaimResponse claimOne(ChatModelVerification job) {
        List<InternalVerificationClaimResponse> claimed = claimService.claim(10);
        return claimed.stream().filter(c -> c.jobId().equals(job.getId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("job not claimable: " + job.getId()));
    }

    private void expireLeaseNow(UUID jobId) {
        jdbcTemplate.update(
                "UPDATE chat_model_verifications SET lease_until = now() - interval '1 second' WHERE id = ?",
                jobId);
    }

    private InternalVerificationResultRequest okResult() {
        return new InternalVerificationResultRequest(null, VerificationResultType.OK, null, null, null, null);
    }

    private InternalVerificationResultRequest withToken(InternalVerificationResultRequest base, UUID token) {
        return new InternalVerificationResultRequest(
                token, base.resultType(), base.errorCode(), base.message(),
                base.embeddingDimension(), base.embeddingFingerprint());
    }

    // ── fencing ──────────────────────────────────────────────────────────

    @Test
    void leaseExpires_secondClaimTakesOver_oldTokenResult_409_rowUnchanged() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);

        InternalVerificationClaimResponse first = claimOne(job);
        expireLeaseNow(job.getId());
        InternalVerificationClaimResponse second = claimOne(job);

        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(second.attempt()).isEqualTo(2);

        assertThatThrownBy(() -> resultService.submitResult(job.getId(), withToken(okResult(), first.leaseToken())))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_LEASE_LOST);

        ChatModel unchangedRow = chatModelRepository.findById(model.getId()).orElseThrow();
        assertThat(unchangedRow.getApiKeyEncrypted()).isEqualTo("sk-old");
        ChatModelVerification current = chatModelVerificationRepository.findById(job.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo(ChatModelVerificationStatus.RUNNING);
        assertThat(current.getLeaseToken()).isEqualTo(second.leaseToken());

        InternalVerificationResultResponse response =
                resultService.submitResult(job.getId(), withToken(okResult(), second.leaseToken()));
        assertThat(response.applied()).isTrue();
        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getApiKeyEncrypted()).isEqualTo("sk-new");
    }

    @Test
    void leaseExpires_noOneReclaims_originalTokenResult_still409_dbClockChecked() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);

        InternalVerificationClaimResponse claimed = claimOne(job);
        expireLeaseNow(job.getId());

        // Same status (RUNNING) and same token — only the DB-clock lease_until check fails.
        assertThatThrownBy(() -> resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken())))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_LEASE_LOST);

        ChatModel unchangedRow = chatModelRepository.findById(model.getId()).orElseThrow();
        assertThat(unchangedRow.getApiKeyEncrypted()).isEqualTo("sk-old");
    }

    @Test
    void resultSentTwice_secondIsDuplicateTrue_notReapplied() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);

        InternalVerificationResultResponse first =
                resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken()));
        assertThat(first.applied()).isTrue();
        assertThat(first.duplicate()).isFalse();

        InternalVerificationResultResponse second =
                resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken()));
        assertThat(second.applied()).isFalse();
        assertThat(second.duplicate()).isTrue();

        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getRevision()).isEqualTo(2);
    }

    @Test
    void duplicateResult_appliesForOkTransientAndFailedBranches() {
        for (VerificationResultType type : VerificationResultType.values()) {
            ChatModel model = activeModel();
            ChatModelVerification job = queuedJob(model);
            InternalVerificationClaimResponse claimed = claimOne(job);
            InternalVerificationResultRequest request = switch (type) {
                case OK -> withToken(okResult(), claimed.leaseToken());
                case TRANSIENT -> new InternalVerificationResultRequest(
                        claimed.leaseToken(), VerificationResultType.TRANSIENT, "rate_limited", "429", null, null);
                case PERMANENT -> new InternalVerificationResultRequest(
                        claimed.leaseToken(), VerificationResultType.PERMANENT, "invalid_api_key", "401", null, null);
            };

            InternalVerificationResultResponse first = resultService.submitResult(job.getId(), request);
            assertThat(first.duplicate()).as(type + " first submit").isFalse();

            InternalVerificationResultResponse second = resultService.submitResult(job.getId(), request);
            assertThat(second.duplicate()).as(type + " retried submit").isTrue();
            assertThat(second.applied()).as(type + " retried submit").isFalse();
        }
    }

    @Test
    void concurrentIdenticalResults_exactlyOneAppliedOneDuplicate_revisionAndVersionIncrementOnce() throws Exception {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);
        InternalVerificationResultRequest request = withToken(okResult(), claimed.leaseToken());

        int publishesBefore = countPublishInvocations();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<InternalVerificationResultResponse>> futures = List.of(
                    pool.submit(() -> {
                        startLatch.await();
                        return resultService.submitResult(job.getId(), request);
                    }),
                    pool.submit(() -> {
                        startLatch.await();
                        return resultService.submitResult(job.getId(), request);
                    }));
            startLatch.countDown();

            List<InternalVerificationResultResponse> results = List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));

            long appliedCount = results.stream().filter(r -> r.applied() && !r.duplicate()).count();
            long duplicateCount = results.stream().filter(InternalVerificationResultResponse::duplicate).count();
            assertThat(appliedCount).isEqualTo(1);
            assertThat(duplicateCount).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getRevision()).isEqualTo(2);
        assertThat(countPublishInvocations() - publishesBefore).isEqualTo(1);
    }

    private int countPublishInvocations() {
        return Mockito.mockingDetails(modelRegistryEventPublisher).getInvocations().size();
    }

    @Test
    void exceptionMidPromote_rollsBackEntirely_retrySameTokenAppliesCleanly() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);
        InternalVerificationResultRequest request = withToken(okResult(), claimed.leaseToken());

        // Swap in a wrapper around the real promotion service that throws exactly once — a plain
        // reflective field swap rather than a Mockito spy, since spying either the transactional
        // service (CGLIB real-method calls bypass the @Transactional advice entirely — a known
        // Mockito/Spring pitfall) or the Spring Data repository interface (Mockito cannot
        // doCallRealMethod on a JDK-proxied interface bean) both fight the test infrastructure
        // instead of exercising the rollback this test actually cares about.
        Object realPromotionService = org.springframework.test.util.ReflectionTestUtils.getField(resultService, "promotionService");
        java.util.concurrent.atomic.AtomicBoolean shouldThrow = new java.util.concurrent.atomic.AtomicBoolean(true);
        ChatModelCandidatePromotionService throwOnceWrapper = j -> {
            if (shouldThrow.compareAndSet(true, false)) {
                throw new RuntimeException("boom — simulated failure mid-promote");
            }
            return ((ChatModelCandidatePromotionService) realPromotionService).promote(j);
        };
        org.springframework.test.util.ReflectionTestUtils.setField(resultService, "promotionService", throwOnceWrapper);
        int publishesBefore = countPublishInvocations();
        try {
            assertThatThrownBy(() -> resultService.submitResult(job.getId(), request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("boom");

            // Whole transaction rolled back — including the last_result_lease_token marker write.
            ChatModelVerification afterFailure = chatModelVerificationRepository.findById(job.getId()).orElseThrow();
            assertThat(afterFailure.getStatus()).isEqualTo(ChatModelVerificationStatus.RUNNING);
            assertThat(afterFailure.getLastResultLeaseToken()).isNull();
            assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getRevision()).isEqualTo(1);
            assertThat(countPublishInvocations() - publishesBefore).isEqualTo(0);

            InternalVerificationResultResponse retried = resultService.submitResult(job.getId(), request);
            assertThat(retried.applied()).isTrue();
            assertThat(retried.duplicate()).isFalse();
            assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getRevision()).isEqualTo(2);
            assertThat(countPublishInvocations() - publishesBefore).isEqualTo(1);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(resultService, "promotionService", realPromotionService);
        }
    }

    // ── rotation race ────────────────────────────────────────────────────

    @Test
    void jobSuperseded_byNewerCandidate_resultWithValidTokenStill409() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);

        // Simulate SA editing the credential while the job is in flight — same lock order the
        // real update() path uses (model, then job).
        supersedeViaNewCandidate(model);

        assertThatThrownBy(() -> resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken())))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_LEASE_LOST);

        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getApiKeyEncrypted()).isEqualTo("sk-old");
    }

    @Test
    void supersededJob_leaseUntilManuallyExtended_stillNeverPromotes() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);

        supersedeViaNewCandidate(model);
        jdbcTemplate.update(
                "UPDATE chat_model_verifications SET lease_until = now() + interval '1 hour' WHERE id = ?",
                job.getId());

        assertThatThrownBy(() -> resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken())))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.VERIFICATION_LEASE_LOST);
    }

    @Test
    void candidateGenerationCasFails_whileJobRunning_jobBecomesSuperseded() {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);

        // Job stays RUNNING with a valid lease/token — only the row's generation moved (bypassing
        // supersede on purpose, to isolate the promote-time CAS from the lease check above it).
        jdbcTemplate.update("UPDATE chat_models SET candidate_generation = candidate_generation + 1 WHERE id = ?",
                model.getId());

        InternalVerificationResultResponse response =
                resultService.submitResult(job.getId(), withToken(okResult(), claimed.leaseToken()));

        assertThat(response.applied()).isFalse();
        assertThat(chatModelVerificationRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ChatModelVerificationStatus.SUPERSEDED);
        assertThat(chatModelRepository.findById(model.getId()).orElseThrow().getApiKeyEncrypted()).isEqualTo("sk-old");
    }

    @Test
    void editAndResultArriveConcurrently_noDeadlock_consistentFinalState() throws Exception {
        ChatModel model = activeModel();
        ChatModelVerification job = queuedJob(model);
        InternalVerificationClaimResponse claimed = claimOne(job);
        InternalVerificationResultRequest request = withToken(okResult(), claimed.leaseToken());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Exception> resultFailure = new AtomicReference<>();
        AtomicReference<Exception> editFailure = new AtomicReference<>();
        try {
            Future<?> resultFuture = pool.submit(() -> {
                try {
                    startLatch.await();
                    resultService.submitResult(job.getId(), request);
                } catch (Exception e) {
                    resultFailure.set(e);
                }
                return null;
            });
            Future<?> editFuture = pool.submit(() -> {
                try {
                    startLatch.await();
                    supersedeViaNewCandidate(model);
                } catch (Exception e) {
                    editFailure.set(e);
                }
                return null;
            });
            startLatch.countDown();
            resultFuture.get(10, TimeUnit.SECONDS);
            editFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // Neither side should ever see a DB deadlock — both lock model-then-job, so at worst one
        // waits for the other, never a cycle.
        if (resultFailure.get() != null) {
            assertThat(resultFailure.get()).isInstanceOf(AppException.class);
        }
        assertThat(editFailure.get()).isNull();

        // Consistent final state: either the result won (row promoted to sk-new, job SUCCEEDED) or
        // the edit won (job SUPERSEDED, row still sk-old plus a fresh candidate job) — never both,
        // never neither, never a torn mix.
        ChatModel finalRow = chatModelRepository.findById(model.getId()).orElseThrow();
        ChatModelVerification finalJob = chatModelVerificationRepository.findById(job.getId()).orElseThrow();
        boolean resultWon = finalJob.getStatus() == ChatModelVerificationStatus.SUCCEEDED
                && "sk-new".equals(finalRow.getApiKeyEncrypted());
        boolean editWon = finalJob.getStatus() == ChatModelVerificationStatus.SUPERSEDED
                && "sk-old".equals(finalRow.getApiKeyEncrypted());
        assertThat(resultWon ^ editWon).as("exactly one side won: resultWon=%s editWon=%s (job=%s, key=%s)",
                resultWon, editWon, finalJob.getStatus(), finalRow.getApiKeyEncrypted()).isTrue();
    }

    /** Mimics ChatModelServiceImpl#update's staged-rotation core: lock model, then supersede job. */
    private void supersedeViaNewCandidate(ChatModel model) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChatModel locked = chatModelRepository.findByIdForUpdate(model.getId()).orElseThrow();
            chatModelVerificationRepository.supersedeOpenJobs(locked.getId());
            locked.setCandidateGeneration(locked.getCandidateGeneration() + 1);
            chatModelRepository.save(locked);
            chatModelVerificationRepository.save(ChatModelVerification.builder()
                    .chatModel(locked)
                    .status(ChatModelVerificationStatus.QUEUED)
                    .candidateGeneration(locked.getCandidateGeneration())
                    .baseRevision(locked.getRevision())
                    .candidateApiKeyEncrypted("sk-second-edit")
                    .attempt(0)
                    .maxAttempts(3)
                    .build());
        });
    }

    // ── stale health ─────────────────────────────────────────────────────

    @Test
    void healthReport_staleCredentialRevision_appliedFalse_noCounterChange_noDisable() {
        ChatModel model = activeModel();
        int publishesBefore = countPublishInvocations();

        CredentialHealthReportRequest staleReport = new CredentialHealthReportRequest(
                model.getRevision() - 1, 1L, CredentialHealthErrorType.PERMANENT,
                "invalid_api_key", "key revoked", OffsetDateTime.now());

        CredentialHealthReportResponse response = modelRegistryInternalService.reportHealth(model.getId(), staleReport);

        assertThat(response.applied()).isFalse();
        ChatModel unchanged = chatModelRepository.findById(model.getId()).orElseThrow();
        assertThat(unchanged.getErrorCount()).isEqualTo(0);
        assertThat(unchanged.getStatus()).isEqualTo(ChatModelStatus.ACTIVE);
        assertThat(countPublishInvocations()).isEqualTo(publishesBefore);
    }
}
