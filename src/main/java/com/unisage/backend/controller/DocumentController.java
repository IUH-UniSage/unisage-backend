package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.CreateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentStatusRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.DocumentResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.document.DocumentService;
import com.unisage.backend.utils.MultipartRequestValidator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private static final String FILE_FIELD_NAME = "file";

    private final DocumentService documentService;
    private final MultipartRequestValidator multipartRequestValidator;

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentResponse>> createDocument(
            @ModelAttribute CreateDocumentRequest request, HttpServletRequest servletRequest) {
        multipartRequestValidator.validateSingleFile(servletRequest, FILE_FIELD_NAME);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(documentService.createDocument(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentResponse>> updateDocument(
            @PathVariable UUID id, @ModelAttribute UpdateDocumentRequest request,
            HttpServletRequest servletRequest) {
        multipartRequestValidator.validateSingleFile(servletRequest, FILE_FIELD_NAME);
        return ResponseEntity.ok(ApiResponse.success(documentService.updateDocument(id, request)));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<DocumentResponse>> updateDocumentStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateDocumentStatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success(documentService.updateStatus(id, request)));
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
