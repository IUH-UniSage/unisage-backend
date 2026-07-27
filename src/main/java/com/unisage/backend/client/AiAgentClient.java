package com.unisage.backend.client;

import com.unisage.backend.dto.ingestion.AgentResponse;
import com.unisage.backend.dto.ingestion.DocumentDraftResponse;
import com.unisage.backend.dto.ingestion.DocumentIngestionConfirmRequest;
import com.unisage.backend.dto.ingestion.DocumentIngestionOptions;
import com.unisage.backend.dto.ingestion.DocumentIngestionResponse;
import com.unisage.backend.dto.request.AiChatRequest;
import com.unisage.backend.exception.AiAgentClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiAgentClient {

    private static final ParameterizedTypeReference<AgentResponse<DocumentDraftResponse>>
            DRAFT_RESPONSE_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AgentResponse<DocumentIngestionResponse>>
            INGESTION_RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

    private final WebClient aiAgentWebClient;

    public Mono<AgentResponse<DocumentDraftResponse>> createIngestionDraft(
            UUID documentId,
            byte[] fileBytes,
            String filename,
            DocumentIngestionOptions options
    ) {
        MultipartBodyBuilder builder = buildUploadBody(
                documentId,
                fileBytes,
                filename,
                options
        );

        return aiAgentWebClient.post()
                .uri("/api/v1/ingestions/drafts")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toAgentException)
                .bodyToMono(DRAFT_RESPONSE_TYPE)
                .doOnError(error -> log.error(
                        "Failed to create ingestion draft documentId={}: {}",
                        documentId,
                        error.getMessage()
                ));
    }

    public Mono<AgentResponse<DocumentIngestionResponse>> ingestDocument(
            UUID documentId,
            byte[] fileBytes,
            String filename,
            DocumentIngestionOptions options
    ) {
        MultipartBodyBuilder builder = buildUploadBody(
                documentId,
                fileBytes,
                filename,
                options
        );

        return aiAgentWebClient.post()
                .uri("/api/v1/ingestions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toAgentException)
                .bodyToMono(INGESTION_RESPONSE_TYPE)
                .doOnError(error -> log.error(
                        "Failed to ingest document documentId={}: {}",
                        documentId,
                        error.getMessage()
                ));
    }

    public Mono<AgentResponse<DocumentIngestionResponse>> confirmIngestion(
            DocumentIngestionConfirmRequest request
    ) {
        return aiAgentWebClient.post()
                .uri("/api/v1/ingestions/confirm")
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toAgentException)
                .bodyToMono(INGESTION_RESPONSE_TYPE)
                .doOnError(error -> log.error(
                        "Failed to confirm ingestion documentId={}: {}",
                        request.documentId(),
                        error.getMessage()
                ));
    }

    public Flux<String> streamChat(String message, String userId, String requestId) {
        AiChatRequest request = new AiChatRequest(message, userId);

        return aiAgentWebClient.post()
                .uri("/api/v1/chat/stream")
                .header("X-Request-ID", requestId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(error -> log.error(
                        "Chat stream failed requestId={}: {}",
                        requestId,
                        error.getMessage()
                ));
    }

    private MultipartBodyBuilder buildUploadBody(
            UUID documentId,
            byte[] fileBytes,
            String filename,
            DocumentIngestionOptions options
    ) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("document_id", documentId.toString());
        builder.part("file", fileBytes)
                .filename(filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        builder.part("faculty", options.faculty());
        builder.part("min_access_level", Integer.toString(options.minAccessLevel()));
        builder.part("version", Integer.toString(options.version()));
        builder.part("strategy", options.strategy());
        builder.part("chunk_size", Integer.toString(options.chunkSize()));
        builder.part("chunk_overlap", Integer.toString(options.chunkOverlap()));
        builder.part("semantic_threshold", Double.toString(options.semanticThreshold()));
        builder.part("rows_per_chunk", Integer.toString(options.rowsPerChunk()));
        builder.part("include_header", Boolean.toString(options.includeHeader()));
        if (options.metadataJson() != null && !options.metadataJson().isBlank()) {
            builder.part("metadata_json", options.metadataJson());
        }
        return builder;
    }

    private Mono<? extends Throwable> toAgentException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new AiAgentClientException(response.statusCode(), body));
    }
}
