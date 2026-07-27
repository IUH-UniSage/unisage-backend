package com.unisage.backend.controller;

import com.unisage.backend.client.AiAgentClient;
import com.unisage.backend.dto.ingestion.AgentResponse;
import com.unisage.backend.dto.ingestion.DocumentDraftResponse;
import com.unisage.backend.dto.ingestion.DocumentIngestionConfirmRequest;
import com.unisage.backend.dto.ingestion.DocumentIngestionOptions;
import com.unisage.backend.dto.ingestion.DocumentIngestionResponse;
import com.unisage.backend.dto.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
@Validated
public class DocumentIngestionController {

    private final AiAgentClient aiAgentClient;

    @PostMapping(
            value = "/ingestions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<ApiResponse<DocumentIngestionResponse>>> ingestDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "document_id", required = false) UUID documentId,
            @RequestParam(defaultValue = "GLOBAL") @NotBlank String faculty,
            @RequestParam(name = "min_access_level", defaultValue = "1") @Min(1) @Max(10)
            int minAccessLevel,
            @RequestParam(defaultValue = "1") @Min(1) int version,
            @RequestParam(defaultValue = "recursive") String strategy,
            @RequestParam(name = "chunk_size", defaultValue = "800") @Min(100) @Max(10_000)
            int chunkSize,
            @RequestParam(name = "chunk_overlap", defaultValue = "120") @Min(0) @Max(2_000)
            int chunkOverlap,
            @RequestParam(name = "semantic_threshold", defaultValue = "0.72")
            @DecimalMin("-1.0") @DecimalMax("1.0") double semanticThreshold,
            @RequestParam(name = "rows_per_chunk", defaultValue = "10") @Min(1) @Max(1_000)
            int rowsPerChunk,
            @RequestParam(name = "include_header", defaultValue = "true") boolean includeHeader,
            @RequestParam(name = "metadata_json", required = false) String metadataJson
    ) throws IOException {
        UUID stableDocumentId = documentId == null ? UUID.randomUUID() : documentId;
        return ingestWithAgent(
                stableDocumentId,
                file,
                options(
                        faculty,
                        minAccessLevel,
                        version,
                        strategy,
                        chunkSize,
                        chunkOverlap,
                        semanticThreshold,
                        rowsPerChunk,
                        includeHeader,
                        metadataJson
                )
        );
    }

    @PostMapping(
            value = "/ingestions/drafts",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Mono<ResponseEntity<ApiResponse<DocumentDraftResponse>>> createIngestionDraft(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "document_id", required = false) UUID documentId,
            @RequestParam(defaultValue = "GLOBAL") @NotBlank String faculty,
            @RequestParam(name = "min_access_level", defaultValue = "1") @Min(1) @Max(10)
            int minAccessLevel,
            @RequestParam(defaultValue = "1") @Min(1) int version,
            @RequestParam(defaultValue = "recursive") String strategy,
            @RequestParam(name = "chunk_size", defaultValue = "800") @Min(100) @Max(10_000)
            int chunkSize,
            @RequestParam(name = "chunk_overlap", defaultValue = "120") @Min(0) @Max(2_000)
            int chunkOverlap,
            @RequestParam(name = "semantic_threshold", defaultValue = "0.72")
            @DecimalMin("-1.0") @DecimalMax("1.0") double semanticThreshold,
            @RequestParam(name = "rows_per_chunk", defaultValue = "10") @Min(1) @Max(1_000)
            int rowsPerChunk,
            @RequestParam(name = "include_header", defaultValue = "true") boolean includeHeader,
            @RequestParam(name = "metadata_json", required = false) String metadataJson
    ) throws IOException {
        UUID stableDocumentId = documentId == null ? UUID.randomUUID() : documentId;
        return aiAgentClient.createIngestionDraft(
                        stableDocumentId,
                        file.getBytes(),
                        filename(file),
                        options(
                                faculty,
                                minAccessLevel,
                                version,
                                strategy,
                                chunkSize,
                                chunkOverlap,
                                semanticThreshold,
                                rowsPerChunk,
                                includeHeader,
                                metadataJson
                        )
                )
                .map(this::successResponse);
    }

    @PostMapping("/ingestions/confirm")
    public Mono<ResponseEntity<ApiResponse<DocumentIngestionResponse>>> confirmIngestion(
            @Valid @RequestBody DocumentIngestionConfirmRequest request
    ) {
        return aiAgentClient.confirmIngestion(request)
                .map(this::successResponse);
    }

    private Mono<ResponseEntity<ApiResponse<DocumentIngestionResponse>>> ingestWithAgent(
            UUID documentId,
            MultipartFile file,
            DocumentIngestionOptions options
    ) throws IOException {
        return aiAgentClient.ingestDocument(
                        documentId,
                        file.getBytes(),
                        filename(file),
                        options
                )
                .map(this::successResponse);
    }

    private DocumentIngestionOptions options(
            String faculty,
            int minAccessLevel,
            int version,
            String strategy,
            int chunkSize,
            int chunkOverlap,
            double semanticThreshold,
            int rowsPerChunk,
            boolean includeHeader,
            String metadataJson
    ) {
        return new DocumentIngestionOptions(
                faculty,
                minAccessLevel,
                version,
                strategy,
                chunkSize,
                chunkOverlap,
                semanticThreshold,
                rowsPerChunk,
                includeHeader,
                metadataJson
        );
    }

    private String filename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        return originalFilename == null || originalFilename.isBlank()
                ? "document"
                : originalFilename;
    }

    private <T> ResponseEntity<ApiResponse<T>> successResponse(AgentResponse<T> response) {
        return ResponseEntity.ok(ApiResponse.success(response.data()));
    }
}
