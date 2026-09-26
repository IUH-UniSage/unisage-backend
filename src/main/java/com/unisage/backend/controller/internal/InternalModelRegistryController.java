package com.unisage.backend.controller.internal;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.request.internal.InternalEmbeddingIndexIdentityRequest;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingIndexIdentityResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistryVersionResponse;
import com.unisage.backend.service.modelregistry.EmbeddingIndexIdentityService;
import com.unisage.backend.service.modelregistry.ModelRegistryInternalService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Internal-only namespace for {@code unisage-agent} — see plan.md "Internal API contract". */
@RestController
@RequestMapping("/internal/model-registry")
@RequiredArgsConstructor
public class InternalModelRegistryController {

    private final EmbeddingIndexIdentityService embeddingIndexIdentityService;
    private final ModelRegistryInternalService modelRegistryInternalService;

    // TODO: wire to ModelRegistryVersionService (Redis + DB version counter) once it exists.
    @GetMapping("/version")
    public InternalModelRegistryVersionResponse version() {
        return new InternalModelRegistryVersionResponse(0L);
    }

    /**
     * Endpoint #3 — Python calls this after a provider error. Always 200; {@code applied: false}
     * means the report's {@code credentialRevision} was stale and got ignored, not an error.
     */
    @PostMapping("/credentials/{id}/health")
    public CredentialHealthReportResponse reportHealth(
            @PathVariable UUID id,
            @Valid @RequestBody CredentialHealthReportRequest request) {
        return modelRegistryInternalService.reportHealth(id, request);
    }

    /** Endpoint #6 — 404 (empty body) if the collection has no identity established yet. */
    @GetMapping("/embedding-index/{collection}/identity")
    public ResponseEntity<InternalEmbeddingIndexIdentityResponse> getEmbeddingIndexIdentity(
            @PathVariable String collection) {
        return embeddingIndexIdentityService.getIdentity(collection)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Endpoint #7 — only-if-absent. An existing identity for this collection maps to 409
     * EMBEDDING_INDEX_IDENTITY_EXISTS (thrown by the service, handled by GlobalExceptionHandler);
     * this method never overwrites one.
     */
    @PutMapping("/embedding-index/{collection}/identity")
    public ResponseEntity<InternalEmbeddingIndexIdentityResponse> putEmbeddingIndexIdentity(
            @PathVariable String collection,
            @Valid @RequestBody InternalEmbeddingIndexIdentityRequest request) {
        InternalEmbeddingIndexIdentityResponse response = embeddingIndexIdentityService.putIdentity(collection, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
