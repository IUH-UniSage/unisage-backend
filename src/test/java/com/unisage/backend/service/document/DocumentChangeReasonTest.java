package com.unisage.backend.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.entity.AccessLevel;
import com.unisage.backend.entity.Department;
import com.unisage.backend.entity.Document;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.DocStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.integration.AgentIndexClient;
import com.unisage.backend.repository.AccessLevelRepository;
import com.unisage.backend.repository.CategoryRepository;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.DocumentVersionRepository;
import com.unisage.backend.repository.UserDepartmentAccessRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.file.FileService;
import com.unisage.backend.utils.SecurityUtil;
import org.mockito.ArgumentCaptor;
import com.unisage.backend.entity.DocumentVersion;
import com.unisage.backend.exception.AppException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

class DocumentChangeReasonTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SecurityUtil securityUtil = mock(SecurityUtil.class);
    private final FileService fileService = mock(FileService.class);
    private final UUID documentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private DocumentServiceImpl service;
    private Document document;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(documentRepository, mock(DepartmentRepository.class),
                mock(CategoryRepository.class), userRepository, mock(AccessLevelRepository.class),
                versionRepository, mock(UserDepartmentAccessRepository.class), securityUtil, fileService,
                mock(AgentIndexClient.class));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        User user = new User();
        user.setId(userId);
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(fileService.upload(any())).thenReturn("new.pdf");

        document = new Document();
        document.setId(documentId);
        document.setSourceUrl("old.pdf");
        document.setFileType("pdf");
        document.setIsPublic(true);
        document.setStatus(DocStatus.PENDING);
        document.setChangeReason("Bản đầu tiên");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    }

    @Test
    void metadataEditStoresTheTrimmedReasonOnTheDocument() {
        service.updateDocument(documentId, UpdateDocumentRequest.builder()
                .title("Quy chế 2026").changeReason("  Sửa tiêu đề  ").build());

        assertThat(document.getChangeReason()).isEqualTo("Sửa tiêu đề");
    }

    @Test
    void replacingTheFileArchivesTheOldVersionWithItsOwnReason() {
        service.updateDocument(documentId, UpdateDocumentRequest.builder()
                .file(new MockMultipartFile("file", "new.pdf", "application/pdf", new byte[] {1}))
                .changeReason("Cập nhật quy chế mới").build());

        ArgumentCaptor<DocumentVersion> archived = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versionRepository).save(archived.capture());
        assertThat(archived.getValue().getChangeReason()).isEqualTo("Bản đầu tiên");
        assertThat(document.getChangeReason()).isEqualTo("Cập nhật quy chế mới");
    }

    @Test
    void rejectsAnOverlongReason() {
        assertThatThrownBy(() -> service.updateDocument(documentId,
                UpdateDocumentRequest.builder().changeReason("x".repeat(501)).build()))
                .isInstanceOf(AppException.class);
    }
}
