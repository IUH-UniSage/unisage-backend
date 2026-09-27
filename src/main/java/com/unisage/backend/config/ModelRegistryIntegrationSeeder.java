package com.unisage.backend.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;
import com.unisage.backend.service.chatmodel.ChatModelService;
import com.unisage.backend.service.chatmodelcandidatepromotion.ChatModelCandidatePromotionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the {@code ChatModel} rows the cross-repo integration harness (todo.md Task 0.5) depends
 * on — {@code unisage-agent/tests/e2e} reads them through the real internal endpoints, never
 * through this class or a raw SQL insert. Only ever registered under profile {@code integration}
 * (see {@link ModelRegistryIntegrationProfileStartupCheck} for the startup rail that keeps this
 * profile out of any real environment).
 *
 * <p>Goes exclusively through {@link ChatModelService}/{@link ChatModelCandidatePromotionService} —
 * the same code paths a real SA request would use — so the API key is encrypted via
 * {@code ApiKeyConverter} exactly the way production data is, and so the row ends up ACTIVE via the
 * same CAS promotion a real verification result would trigger, not a hand-rolled shortcut. The
 * normal path is create() (PENDING + a QUEUED job) then promote() that job directly — this
 * intentionally skips the claim/lease HTTP round trip (there is no live verifier in the harness by
 * design; the whole point of this seeder is for credentials to be immediately usable).
 *
 * <p>Idempotent two ways, matching todo.md Task 0.5's "your call, but document it": {@link #seed()}
 * itself is restart-safe (a check-before-insert — does nothing if any {@code ChatModel} row already
 * exists), while {@code POST /internal/test/registry/reset} always deletes every row first and then
 * calls {@link #seed()} again, so the "already seeded" guard never blocks a deliberate reset.
 */
@Component
@Profile("integration")
@RequiredArgsConstructor
@Slf4j
public class ModelRegistryIntegrationSeeder implements CommandLineRunner {

    private static final String LLM_PROVIDER = "openai";
    private static final Integer MAX_RPM = 60;
    /** Arbitrary but fixed — no established embedding index identity exists yet at seed time, so
     *  the exact value has no effect on whether the embedding row can activate (see
     *  {@code ChatModelCandidatePromotionServiceImpl#wouldChangeEmbeddingIdentity}). */
    private static final Integer EMBEDDING_DIMENSION = 1536;

    private final ChatModelService chatModelService;
    private final ChatModelRepository chatModelRepository;
    private final ChatModelVerificationRepository chatModelVerificationRepository;
    private final ChatModelCandidatePromotionService promotionService;

    /**
     * Base URL every seeded credential points at. Defaults to a loopback address that resolves in
     * a plain unit/integration test run; the real docker-compose harness (todo.md Task 0.5)
     * overrides this to the fake provider's in-network hostname and adds that hostname to
     * {@code MODEL_REGISTRY_URL_ALLOWLIST}.
     */
    @Value("${app.model-registry.integration-seed.fake-provider-base-url:http://localhost:8000/v1}")
    private String fakeProviderBaseUrl;

    @Override
    @Transactional
    public void run(String... args) {
        seed();
    }

    /** @see ModelRegistryIntegrationSeeder class javadoc for the idempotency contract. */
    @Transactional
    public void seed() {
        if (chatModelRepository.count() > 0) {
            log.info(">>> ModelRegistryIntegrationSeeder: rows already present — skipping.");
            return;
        }

        log.info(">>> ModelRegistryIntegrationSeeder: seeding CHAT(x2)/EMBEDDING/EXTRACTION credentials.");
        seedChat(1);
        seedChat(2);
        seedEmbedding();
        seedSimple(ChatModelPurpose.EXTRACTION, "seed-extraction-key", null);
    }

    private void seedChat(int priority) {
        seedSimple(ChatModelPurpose.CHAT, "seed-chat-key-" + priority, priority);
    }

    private void seedSimple(ChatModelPurpose purpose, String apiKey, Integer priority) {
        ChatModelResponse created = chatModelService.create(ChatModelRequest.builder()
                .modelPurpose(purpose)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider(LLM_PROVIDER)
                .llmModelName(defaultModelNameFor(purpose))
                .apiKey(apiKey)
                .apiBaseUrl(fakeProviderBaseUrl)
                .maxRpm(MAX_RPM)
                .priority(priority)
                .build());

        ChatModelVerification job = latestJobFor(created.id());
        promotionService.promote(job);
    }

    private void seedEmbedding() {
        ChatModelResponse created = chatModelService.create(ChatModelRequest.builder()
                .modelPurpose(ChatModelPurpose.EMBEDDING)
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider(LLM_PROVIDER)
                .llmModelName(defaultModelNameFor(ChatModelPurpose.EMBEDDING))
                .apiKey("seed-embedding-key")
                .apiBaseUrl(fakeProviderBaseUrl)
                .maxRpm(MAX_RPM)
                .build());

        ChatModelVerification job = latestJobFor(created.id());
        job.setEmbeddingDimension(EMBEDDING_DIMENSION);
        promotionService.promote(job);

        // EMBEDDING never auto-activates on promote (plan.md "State machine" — PENDING/DISABLED ->
        // INACTIVE for this purpose); the harness needs it immediately ACTIVE, so take the exact
        // same SA-triggered path a human operator would.
        chatModelService.updateStatus(created.id(), ChatModelStatus.ACTIVE);
    }

    private ChatModelVerification latestJobFor(UUID chatModelId) {
        return chatModelVerificationRepository.findFirstByChatModelIdOrderByCreatedAtDesc(chatModelId)
                .orElseThrow(() -> new IllegalStateException(
                        "ChatModelService.create() did not leave a verification job for " + chatModelId));
    }

    private String defaultModelNameFor(ChatModelPurpose purpose) {
        return switch (purpose) {
            case CHAT -> "gpt-4o-mini";
            case EMBEDDING -> "text-embedding-3-small";
            case EXTRACTION -> "gpt-4o-mini";
        };
    }
}
