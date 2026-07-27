package com.unisage.backend.client;

import com.sun.net.httpserver.HttpServer;
import com.unisage.backend.dto.ingestion.AgentResponse;
import com.unisage.backend.dto.ingestion.DocumentChunkDraft;
import com.unisage.backend.dto.ingestion.DocumentDraftResponse;
import com.unisage.backend.dto.ingestion.DocumentIngestionConfirmRequest;
import com.unisage.backend.dto.ingestion.DocumentIngestionOptions;
import com.unisage.backend.dto.ingestion.DocumentIngestionResponse;
import com.unisage.backend.exception.AiAgentClientException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentClientTest {

    private static final UUID DOCUMENT_ID =
            UUID.fromString("d6cb2458-7185-4d96-8adc-cdb650e4ff84");

    @Test
    void createIngestionDraftUsesPluralResourceAndMapsSnakeCaseResponse() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        AiAgentClient client = clientReturning(
                """
                {
                  "status": "success",
                  "data": {
                    "document_id": "d6cb2458-7185-4d96-8adc-cdb650e4ff84",
                    "source": "handbook.txt",
                    "strategy": "recursive",
                    "metadata": {"faculty": "CNTT"},
                    "total_chunks": 1,
                    "chunks": [{
                      "index": 0,
                      "content": "UniSage handbook",
                      "token_count": 3,
                      "metadata": {"chunk_index": 0}
                    }]
                  },
                  "message": "Chunk draft created."
                }
                """,
                HttpStatus.OK,
                capturedRequest
        );

        AgentResponse<DocumentDraftResponse> response = client.createIngestionDraft(
                DOCUMENT_ID,
                "UniSage handbook".getBytes(),
                "handbook.txt",
                defaultOptions()
        ).block();

        assertNotNull(response);
        assertEquals(DOCUMENT_ID, response.data().documentId());
        assertEquals(1, response.data().totalChunks());
        assertEquals(3, response.data().chunks().get(0).tokenCount());
        assertEquals("/api/v1/ingestions/drafts", capturedRequest.get().url().getPath());
        assertTrue(MediaType.MULTIPART_FORM_DATA.isCompatibleWith(
                capturedRequest.get().headers().getContentType()
        ));
    }

    @Test
    void createIngestionDraftSerializesAgentMultipartContract() throws Exception {
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/ingestions/drafts", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] response = """
                    {
                      "status": "success",
                      "data": {
                        "document_id": "d6cb2458-7185-4d96-8adc-cdb650e4ff84",
                        "source": "handbook.txt",
                        "strategy": "recursive",
                        "metadata": {"faculty": "CNTT"},
                        "total_chunks": 1,
                        "chunks": [{
                          "index": 0,
                          "content": "UniSage handbook",
                          "token_count": 3,
                          "metadata": {"chunk_index": 0}
                        }]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            WebClient webClient = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();
            AiAgentClient client = new AiAgentClient(webClient);

            client.createIngestionDraft(
                    DOCUMENT_ID,
                    "UniSage handbook".getBytes(StandardCharsets.UTF_8),
                    "handbook.txt",
                    defaultOptions()
            ).block();

            assertTrue(capturedContentType.get().startsWith("multipart/form-data;"));
            assertTrue(capturedBody.get().contains("name=\"document_id\""));
            assertTrue(capturedBody.get().contains(DOCUMENT_ID.toString()));
            assertTrue(capturedBody.get().contains("name=\"file\""));
            assertTrue(capturedBody.get().contains("filename=\"handbook.txt\""));
            assertTrue(capturedBody.get().contains("name=\"min_access_level\""));
            assertTrue(capturedBody.get().contains("name=\"chunk_overlap\""));
            assertTrue(capturedBody.get().contains("name=\"include_header\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ingestDocumentUsesIngestionsCollection() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        AiAgentClient client = clientReturning(
                ingestionResponseJson(),
                HttpStatus.CREATED,
                capturedRequest
        );

        AgentResponse<DocumentIngestionResponse> response = client.ingestDocument(
                DOCUMENT_ID,
                "UniSage handbook".getBytes(),
                "handbook.txt",
                defaultOptions()
        ).block();

        assertNotNull(response);
        assertEquals("COMPLETED", response.data().status());
        assertEquals(4, response.data().chunkCount());
        assertEquals("/api/v1/ingestions", capturedRequest.get().url().getPath());
    }

    @Test
    void confirmIngestionUsesConfirmEndpoint() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        AiAgentClient client = clientReturning(
                ingestionResponseJson(),
                HttpStatus.CREATED,
                capturedRequest
        );
        DocumentIngestionConfirmRequest request = new DocumentIngestionConfirmRequest(
                DOCUMENT_ID,
                "handbook.txt",
                Map.of("faculty", "CNTT", "min_access_level", 1, "version", 1),
                List.of(new DocumentChunkDraft(
                        0,
                        "UniSage handbook",
                        3,
                        Map.of("chunk_index", 0)
                ))
        );

        AgentResponse<DocumentIngestionResponse> response =
                client.confirmIngestion(request).block();

        assertNotNull(response);
        assertEquals(DOCUMENT_ID, response.data().documentId());
        assertEquals("/api/v1/ingestions/confirm", capturedRequest.get().url().getPath());
    }

    @Test
    void confirmIngestionSerializesSnakeCaseJsonContract() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/ingestions/confirm", exchange -> {
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] response = ingestionResponseJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(HttpStatus.CREATED.value(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AiAgentClient client = new AiAgentClient(
                    WebClient.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                            .build()
            );
            DocumentIngestionConfirmRequest request = new DocumentIngestionConfirmRequest(
                    DOCUMENT_ID,
                    "handbook.txt",
                    Map.of("faculty", "CNTT", "min_access_level", 1, "version", 1),
                    List.of(new DocumentChunkDraft(
                            0,
                            "UniSage handbook",
                            3,
                            Map.of("chunk_index", 0)
                    ))
            );

            client.confirmIngestion(request).block();

            assertTrue(capturedBody.get().contains("\"document_id\""));
            assertTrue(capturedBody.get().contains("\"token_count\""));
            assertFalse(capturedBody.get().contains("\"documentId\""));
            assertFalse(capturedBody.get().contains("\"tokenCount\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agentValidationErrorIsConvertedToTypedException() {
        AiAgentClient client = clientReturning(
                """
                {"status":"error","error_code":"VALIDATION_ERROR","message":"Invalid request"}
                """,
                HttpStatus.BAD_REQUEST,
                new AtomicReference<>()
        );

        AiAgentClientException exception = assertThrows(
                AiAgentClientException.class,
                () -> client.ingestDocument(
                        DOCUMENT_ID,
                        "content".getBytes(),
                        "handbook.txt",
                        defaultOptions()
                ).block()
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getUpstreamStatus());
        assertTrue(exception.getResponseBody().contains("VALIDATION_ERROR"));
    }

    private AiAgentClient clientReturning(
            String body,
            HttpStatus status,
            AtomicReference<ClientRequest> capturedRequest
    ) {
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(
                    ClientResponse.create(status)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build()
            );
        };
        WebClient webClient = WebClient.builder()
                .exchangeFunction(exchangeFunction)
                .build();
        return new AiAgentClient(webClient);
    }

    private DocumentIngestionOptions defaultOptions() {
        return new DocumentIngestionOptions(
                "CNTT",
                1,
                1,
                "recursive",
                800,
                120,
                0.72,
                10,
                true,
                null
        );
    }

    private String ingestionResponseJson() {
        return """
                {
                  "status": "success",
                  "data": {
                    "document_id": "d6cb2458-7185-4d96-8adc-cdb650e4ff84",
                    "status": "COMPLETED",
                    "chunk_count": 4,
                    "embedding_model": "sentence-transformers/all-MiniLM-L6-v2"
                  },
                  "message": "Document ingested successfully."
                }
                """;
    }
}
