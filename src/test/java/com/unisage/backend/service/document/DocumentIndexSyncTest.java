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

/** UNISAGE-103 R2: document edits keep the agent's vector index in step. */
class DocumentIndexSyncTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SecurityUtil securityUtil = mock(SecurityUtil.class);
    private final FileService fileService = mock(FileService.class);
    private final AgentIndexClient agentIndexClient = mock(AgentIndexClient.class);
    private final UUID documentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Department oldDepartment = department();
    private final Department newDepartment = department();

    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(
                documentRepository,
                departmentRepository,
                mock(CategoryRepository.class),
                userRepository,
                mock(AccessLevelRepository.class),
                mock(DocumentVersionRepository.class),
                mock(UserDepartmentAccessRepository.class),
                securityUtil,
                fileService,
                agentIndexClient);
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(departmentRepository.findById(newDepartment.getId())).thenReturn(Optional.of(newDepartment));
        User user = new User();
        user.setId(userId);
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private static Department department() {
        Department department = new Department();
        department.setId(UUID.randomUUID());
        return department;
    }

    private Document stored(DocStatus status) {
        AccessLevel level = new AccessLevel();
        level.setLevel(2);
        Document document = new Document();
        document.setId(documentId);
        document.setSourceUrl("old.pdf");
        document.setIsPublic(false);
        document.setDocPackage(oldDepartment);
        document.setMinAccessLevel(level);
        document.setStatus(status);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        return document;
    }

    private static UpdateDocumentRequest.UpdateDocumentRequestBuilder request() {
        return UpdateDocumentRequest.builder();
    }

    @Test
    void departmentChangeRewritesThePayloadOfAnIndexedDocument() {
        stored(DocStatus.COMPLETED);

        service.updateDocument(documentId, request().docPackageId(newDepartment.getId()).build());

        verify(agentIndexClient).updateIndexMetadata(documentId, newDepartment.getId(), 2, false);
        verify(agentIndexClient, never()).deleteIndex(any());
    }

    @Test
    void goingPublicRewritesThePayloadWithAccessLevelZero() {
        stored(DocStatus.COMPLETED);

        service.updateDocument(documentId, request().isPublic(true).build());

        verify(agentIndexClient).updateIndexMetadata(documentId, oldDepartment.getId(), 0, true);
    }

    @Test
    void titleOnlyEditLeavesTheIndexAlone() {
        stored(DocStatus.COMPLETED);

        service.updateDocument(documentId, request().title("Tên mới").build());

        verifyNoInteractions(agentIndexClient);
    }

    @Test
    void pendingDocumentHasNoPayloadToRewrite() {
        stored(DocStatus.PENDING);

        service.updateDocument(documentId, request().docPackageId(newDepartment.getId()).build());

        verifyNoInteractions(agentIndexClient);
    }

    @Test
    void newFileDropsTheIndexAndMarksTheDocumentOutdated() {
        Document document = stored(DocStatus.COMPLETED);
        when(fileService.upload(any())).thenReturn("new.pdf");

        service.updateDocument(documentId, request()
                .file(new MockMultipartFile("file", "new.pdf", "application/pdf", new byte[] {1}))
                .build());

        verify(agentIndexClient).deleteIndex(documentId);
        verify(agentIndexClient, never()).updateIndexMetadata(any(), any(), anyInt(), anyBoolean());
        assertThat(document.getStatus()).isEqualTo(DocStatus.OUTDATED);
    }

    @Test
    void newFileOnAPendingDocumentKeepsItPending() {
        Document document = stored(DocStatus.PENDING);
        when(fileService.upload(any())).thenReturn("new.pdf");

        service.updateDocument(documentId, request()
                .file(new MockMultipartFile("file", "new.pdf", "application/pdf", new byte[] {1}))
                .build());

        verify(agentIndexClient).deleteIndex(documentId);
        assertThat(document.getStatus()).isEqualTo(DocStatus.PENDING);
    }

    @Test
    void agentFailureFailsTheEditBeforeAnythingIsSaved() {
        stored(DocStatus.COMPLETED);
        doThrow(new AppException(ErrorCode.DOCUMENT_INDEX_SYNC_FAILED))
                .when(agentIndexClient).updateIndexMetadata(any(), any(), anyInt(), anyBoolean());

        assertThatThrownBy(() -> service.updateDocument(
                        documentId, request().docPackageId(newDepartment.getId()).build()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.DOCUMENT_INDEX_SYNC_FAILED);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void softDeleteDropsTheIndex() {
        stored(DocStatus.COMPLETED);

        service.softDelete(documentId);

        verify(agentIndexClient).deleteIndex(documentId);
    }
}
