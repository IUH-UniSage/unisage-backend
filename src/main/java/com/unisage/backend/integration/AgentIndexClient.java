package com.unisage.backend.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps unisage-agent's vector index in step with document edits — changes/17-10-2026-Embedding-Reindex/plan.md
 * §2.4. Every failure is {@link ErrorCode#DOCUMENT_INDEX_SYNC_FAILED} so the caller's transaction rolls
 * back: a payload left on the old department/access level would serve the document to the wrong people.
 */
@Component
@Slf4j
public class AgentIndexClient {

    private static final String SECRET_HEADER = "X-Internal-Secret";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalSecret;

    public AgentIndexClient(
            @Value("${app.agent.base-url:http://localhost:8402/api/v1}") String baseUrl,
            @Value("${app.internal.secret-key}") String internalSecret,
            @Value("${app.agent.timeout-ms:10000}") int timeoutMs) {
        // JDK HttpClient: HttpURLConnection (SimpleClientHttpRequestFactory) cannot send PATCH.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.internalSecret = internalSecret;
    }

    /** Rewrites the access payload of every point of the document. */
    public void updateIndexMetadata(UUID documentId, UUID departmentId, int accessLevel, boolean isPublic) {
        Map<String, Object> body = new HashMap<>();
        body.put("department_id", departmentId != null ? departmentId.toString() : null);
        body.put("access_level", accessLevel);
        body.put("is_public", isPublic);
        call(HttpMethod.PATCH, "/internal/documents/" + documentId + "/index-metadata", body);
    }

    /** Drops every point and the chunking draft of the document. */
    public void deleteIndex(UUID documentId) {
        call(HttpMethod.DELETE, "/internal/documents/" + documentId + "/index", null);
    }

    private void call(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SECRET_HEADER, internalSecret);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientException exc) {
            log.warn("unisage-agent index sync {} {} failed: {}", method, path, exc.getMessage());
            throw new AppException(ErrorCode.DOCUMENT_INDEX_SYNC_FAILED);
        }
    }
}
