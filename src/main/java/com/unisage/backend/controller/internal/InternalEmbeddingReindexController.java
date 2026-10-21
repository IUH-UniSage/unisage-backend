package com.unisage.backend.controller.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexProgressRequest;
import com.unisage.backend.dto.request.internal.InternalEmbeddingReindexResultRequest;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexClaimResponse;
import com.unisage.backend.dto.response.internal.InternalEmbeddingReindexStatusResponse;
import com.unisage.backend.service.embeddingreindex.EmbeddingReindexInternalService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * unisage-agent's re-index worker loop — changes/17-10-2026-Embedding-Reindex/plan.md §2.3. The claim
 * response carries the target credential in plaintext; {@code InternalResponseHeadersFilter}
 * {@code no-store}s every {@code /internal/**} response.
 */
@RestController
@RequestMapping("/internal/embedding-reindex")
@RequiredArgsConstructor
public class InternalEmbeddingReindexController {

    private final EmbeddingReindexInternalService embeddingReindexInternalService;

    @PostMapping("/claim")
    public List<InternalEmbeddingReindexClaimResponse> claim(@RequestParam(defaultValue = "1") int limit) {
        return embeddingReindexInternalService.claim(limit);
    }

    @PostMapping("/{runId}/progress")
    public InternalEmbeddingReindexStatusResponse progress(
            @PathVariable UUID runId,
            @Valid @RequestBody InternalEmbeddingReindexProgressRequest request) {
        return embeddingReindexInternalService.progress(runId, request);
    }

    @PostMapping("/{runId}/result")
    public InternalEmbeddingReindexStatusResponse result(
            @PathVariable UUID runId,
            @Valid @RequestBody InternalEmbeddingReindexResultRequest request) {
        return embeddingReindexInternalService.result(runId, request);
    }
}
