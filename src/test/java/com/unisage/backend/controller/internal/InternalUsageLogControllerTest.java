package com.unisage.backend.controller.internal;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.repository.RequestUsageLineRepository;
import com.unisage.backend.repository.RequestUsageLogRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 2 acceptance: internal auth (5 cases) + idempotent ingest. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalUsageLogControllerTest {

    private static final String SECRET = "unisage-internal-secret-key-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.internal.allowed-cidrs", () -> "127.0.0.1/32,::1/128");
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Autowired
    private RequestUsageLogRepository requestUsageLogRepository;

    @Autowired
    private RequestUsageLineRepository requestUsageLineRepository;

    private String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    private String samplePayload(UUID requestId) {
        OffsetDateTime now = OffsetDateTime.now();
        return """
                {
                  "requestId": "%s",
                  "purpose": "CHAT",
                  "status": "SUCCESS",
                  "startedAt": "%s",
                  "finishedAt": "%s",
                  "lines": [
                    {
                      "seq": 0,
                      "nodeName": "GenerationSynthesisNode",
                      "attempt": 0,
                      "provider": "openai",
                      "modelName": "gpt-4o-mini",
                      "sourceType": "CLOUD_API",
                      "inputTokens": 100,
                      "outputTokens": 50,
                      "cachedTokens": 0,
                      "costUsd": 0.0012,
                      "estimatedCostUsd": 0.0012,
                      "costStatus": "PRICED",
                      "status": "SUCCESS",
                      "occurredAt": "%s"
                    }
                  ]
                }
                """.formatted(requestId, now, now, now);
    }

    private HttpHeaders headersWithSecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Internal-Secret", SECRET);
        return headers;
    }

    @Test
    void noSecret_returns403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(samplePayload(UUID.randomUUID()), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void wrongSecret_returns403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Internal-Secret", "not-the-real-secret");
        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(samplePayload(UUID.randomUUID()), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_returns200() {
        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(samplePayload(UUID.randomUUID()), headersWithSecret()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"duplicate\":false");
    }

    @Test
    void correctSecret_callingUserFacingEndpoint_isStillDeniedByRbac() {
        // A verified internal secret only grants /internal/**; a user-facing, JWT-gated endpoint
        // must still reject it (DynamicAuthorizationManager only checks the attribute under
        // /internal/**, everything else goes through the normal RBAC branch).
        ResponseEntity<String> response = restTemplate.exchange(url("/users"), HttpMethod.GET,
                new HttpEntity<>(null, headersWithSecret()), String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void jwtWithoutSecret_returns403ForInternalEndpoint() {
        // No real JWT infra exercised here - the point is that *any* call to /internal/** without
        // a verified secret is denied, regardless of what other auth material it carries.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Bearer not-a-real-jwt");
        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(samplePayload(UUID.randomUUID()), headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void sendingSamePayloadTwice_yieldsOneParentAndCorrectLineCount() {
        UUID requestId = UUID.randomUUID();
        String payload = samplePayload(requestId);

        ResponseEntity<String> first = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(payload, headersWithSecret()), String.class);
        ResponseEntity<String> second = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(payload, headersWithSecret()), String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).contains("\"duplicate\":true");

        var log = requestUsageLogRepository.findByRequestId(requestId).orElseThrow();
        assertThat(requestUsageLineRepository.findByUsageLogIdOrderBySeq(log.getId())).hasSize(1);
    }

    @Test
    void concurrentIdenticalRequestId_yieldsOneParent() throws InterruptedException {
        UUID requestId = UUID.randomUUID();
        String payload = samplePayload(requestId);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger okCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"),
                            HttpMethod.POST, new HttpEntity<>(payload, headersWithSecret()), String.class);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        okCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(okCount.get()).isEqualTo(threads);
        var log = requestUsageLogRepository.findByRequestId(requestId).orElseThrow();
        assertThat(requestUsageLineRepository.findByUsageLogIdOrderBySeq(log.getId())).hasSize(1);
    }

    @Test
    void emptyLines_isRejected() {
        String payload = """
                {
                  "requestId": "%s",
                  "purpose": "CHAT",
                  "status": "SUCCESS",
                  "startedAt": "%s",
                  "lines": []
                }
                """.formatted(UUID.randomUUID(), OffsetDateTime.now());

        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(payload, headersWithSecret()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void duplicateSeqWithinPayload_isRejected() {
        UUID requestId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String payload = """
                {
                  "requestId": "%s",
                  "purpose": "CHAT",
                  "status": "SUCCESS",
                  "startedAt": "%s",
                  "lines": [
                    {"seq": 0, "nodeName": "n", "attempt": 0, "sourceType": "SELF_HOSTED",
                     "inputTokens": 1, "outputTokens": 1, "cachedTokens": 0,
                     "estimatedCostUsd": 0, "costStatus": "FREE", "status": "SUCCESS", "occurredAt": "%s"},
                    {"seq": 0, "nodeName": "n2", "attempt": 0, "sourceType": "SELF_HOSTED",
                     "inputTokens": 1, "outputTokens": 1, "cachedTokens": 0,
                     "estimatedCostUsd": 0, "costStatus": "FREE", "status": "SUCCESS", "occurredAt": "%s"}
                  ]
                }
                """.formatted(requestId, now, now, now);

        ResponseEntity<String> response = restTemplate.exchange(url("/internal/usage-logs"), HttpMethod.POST,
                new HttpEntity<>(payload, headersWithSecret()), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
