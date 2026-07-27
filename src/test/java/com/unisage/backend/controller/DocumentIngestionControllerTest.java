package com.unisage.backend.controller;

import com.unisage.backend.client.AiAgentClient;
import com.unisage.backend.dto.ingestion.AgentResponse;
import com.unisage.backend.dto.ingestion.DocumentIngestionOptions;
import com.unisage.backend.dto.ingestion.DocumentIngestionResponse;
import com.unisage.backend.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionControllerTest {

    @Mock
    private AiAgentClient aiAgentClient;

    @Test
    void ingestDocumentGeneratesStableIdWhenCallerOmitsIt() throws IOException {
        ArgumentCaptor<UUID> documentIdCaptor = ArgumentCaptor.forClass(UUID.class);
        when(aiAgentClient.ingestDocument(
                any(UUID.class),
                any(byte[].class),
                anyString(),
                any(DocumentIngestionOptions.class)
        )).thenAnswer(invocation -> {
            UUID documentId = invocation.getArgument(0);
            return Mono.just(new AgentResponse<>(
                    "success",
                    new DocumentIngestionResponse(
                            documentId,
                            "COMPLETED",
                            1,
                            "sentence-transformers/all-MiniLM-L6-v2"
                    ),
                    "Document ingested successfully."
            ));
        });
        DocumentIngestionController controller =
                new DocumentIngestionController(aiAgentClient);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "handbook.txt",
                "text/plain",
                "UniSage handbook".getBytes()
        );

        ResponseEntity<ApiResponse<DocumentIngestionResponse>> response =
                controller.ingestDocument(
                        file,
                        null,
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
                ).block();

        verify(aiAgentClient).ingestDocument(
                documentIdCaptor.capture(),
                eq("UniSage handbook".getBytes()),
                eq("handbook.txt"),
                any(DocumentIngestionOptions.class)
        );
        assertNotNull(response);
        assertNotNull(response.getBody());
        assertEquals(documentIdCaptor.getValue(), response.getBody().data().documentId());
        assertEquals(1000, response.getBody().code());
    }
}
