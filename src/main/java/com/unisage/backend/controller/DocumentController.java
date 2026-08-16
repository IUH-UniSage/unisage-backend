package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.CreateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.DocumentResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.document.DocumentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentResponse>> createDocument(
            @ModelAttribute CreateDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(documentService.createDocument(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> updateDocument(
            @PathVariable UUID id, @ModelAttribute UpdateDocumentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(documentService.updateDocument(id, request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocument(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(documentService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<DocumentResponse>>>> getAllDocuments(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(documentService.getAll(pageable)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable UUID id) {
        documentService.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
