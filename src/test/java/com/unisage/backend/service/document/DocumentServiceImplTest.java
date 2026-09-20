package com.unisage.backend.service.document;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.dto.response.CitationDocumentResponse;
import com.unisage.backend.entity.Document;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AccessLevelRepository;
import com.unisage.backend.repository.CategoryRepository;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.file.FileService;
import com.unisage.backend.utils.SecurityUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentServiceImplTest {

    private static final String OBJECT_KEY =
            "a63a8c6f-3715-4d03-a464-13116e18bed7_Quyet dinh 1035 QD DHCN Hoc phi 2025-2026.pdf";

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final FileService fileService = mock(FileService.class);
    private final UUID documentId = UUID.randomUUID();

    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(
                documentRepository,
                mock(DepartmentRepository.class),
                mock(CategoryRepository.class),
                mock(UserRepository.class),
                mock(AccessLevelRepository.class),
                mock(SecurityUtil.class),
                fileService);
    }

    private Document document(String sourceUrl) {
        Document document = new Document();
        document.setId(documentId);
        document.setTitle("Quyết định 1035 QĐ-ĐHCN Học phí 2025-2026");
        document.setFileType("PDF");
        document.setSourceUrl(sourceUrl);
        document.setIsPublic(true);
        return document;
    }

    @Test
    void getCitationById_returnsPreviewFieldsWithoutExposingObjectKey() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document(OBJECT_KEY)));
        when(fileService.getPresignedUrl(OBJECT_KEY)).thenReturn("https://minio.test/signed");

        CitationDocumentResponse response = service.getCitationById(documentId);

        assertThat(response.id()).isEqualTo(documentId);
        assertThat(response.title()).isEqualTo("Quyết định 1035 QĐ-ĐHCN Học phí 2025-2026");
        assertThat(response.fileType()).isEqualTo("PDF");
        assertThat(response.fileUrl()).isEqualTo("https://minio.test/signed");
        assertThat(response.fileName()).isEqualTo("Quyet dinh 1035 QD DHCN Hoc phi 2025-2026.pdf");
        assertThat(response.toString()).doesNotContain("a63a8c6f");
    }

    @Test
    void getCitationById_hasNullFileUrlWhenDocumentHasNoFile() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document(null)));

        CitationDocumentResponse response = service.getCitationById(documentId);

        assertThat(response.fileUrl()).isNull();
        assertThat(response.fileName()).isNull();
    }

    @Test
    void getCitationById_throwsNotFoundWhenMissing() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCitationById(documentId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void getCitationById_throwsNotFoundWhenSoftDeleted() {
        Document deleted = document(OBJECT_KEY);
        deleted.setDeletedAt(LocalDateTime.now());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getCitationById(documentId))
                .isInstanceOf(AppException.class);
    }
}
