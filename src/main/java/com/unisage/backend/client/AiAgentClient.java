package com.unisage.backend.client;

import com.unisage.backend.dto.request.*;
import com.unisage.backend.dto.response.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiAgentClient {

    private final WebClient aiAgentWebClient;

    public Mono<DraftResponse> extractDraftContent(byte[] fileBytes, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", fileBytes).filename(filename);

        return aiAgentWebClient.post()
                .uri("/api/v1/ingest/draft")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(DraftResponse.class)
                .doOnError(e -> log.error("Lỗi khi đọc file Draft từ Python: {}", e.getMessage()));
    }

    public Mono<ChunkResponse> chunkContent(String content, int chunkSize, int chunkOverlap) {
        ChunkRequest request = new ChunkRequest(content, chunkSize, chunkOverlap);

        return aiAgentWebClient.post()
                .uri("/api/v1/ingest/chunk")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChunkResponse.class)
                .doOnError(e -> log.error("Lỗi khi Chunking từ Python: {}", e.getMessage()));
    }

    public Mono<EmbedResponse> embedAndSave(String documentId, String userId, List<String> chunks) {
        EmbedRequest request = new EmbedRequest(documentId, userId, chunks);

        return aiAgentWebClient.post()
                .uri("/api/v1/ingest/embed")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .doOnError(e -> log.error("Lỗi khi Embed & Save Qdrant từ Python: {}", e.getMessage()));
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
                .doOnError(e -> log.error("Lỗi Chat Stream [Trace ID: {}]: {}", requestId, e.getMessage()));
    }
}
