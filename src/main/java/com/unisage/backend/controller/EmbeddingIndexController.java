package com.unisage.backend.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.EmbeddingReindexRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.EmbeddingIndexResponse;
import com.unisage.backend.dto.response.EmbeddingReindexRunResponse;
import com.unisage.backend.service.embeddingreindex.EmbeddingReindexService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The live embedding index and its re-index runs — changes/17-10-2026-Embedding-Reindex/plan.md
 * §2.2. Under {@code /chat-models} so the CHAT_MODEL permission group covers it.
 */
@RestController
@RequestMapping("/chat-models/embedding-index")
@RequiredArgsConstructor
public class EmbeddingIndexController {

    private final EmbeddingReindexService embeddingReindexService;

    @GetMapping
    public ResponseEntity<ApiResponse<EmbeddingIndexResponse>> getIndex() {
        return ResponseEntity.ok(ApiResponse.success(embeddingReindexService.getIndex()));
    }

    @PostMapping("/reindex")
    public ResponseEntity<ApiResponse<EmbeddingReindexRunResponse>> start(
            @Valid @RequestBody EmbeddingReindexRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(embeddingReindexService.start(request)));
    }

    @PostMapping("/reindex/{runId}/cancel")
    public ResponseEntity<ApiResponse<EmbeddingReindexRunResponse>> cancel(@PathVariable UUID runId) {
        return ResponseEntity.ok(ApiResponse.success(embeddingReindexService.cancel(runId)));
    }
}
