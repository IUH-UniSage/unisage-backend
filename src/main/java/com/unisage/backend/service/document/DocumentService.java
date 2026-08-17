package com.unisage.backend.service.document;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.request.CreateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.dto.response.DocumentResponse;
import com.unisage.backend.dto.response.PageResponse;

public interface DocumentService {

    DocumentResponse createDocument(CreateDocumentRequest request);

    DocumentResponse updateDocument(UUID id, UpdateDocumentRequest request);

    DocumentResponse getById(UUID id);

    PageResponse<List<DocumentResponse>> getAll(Pageable pageable);

    void softDelete(UUID id);
}
