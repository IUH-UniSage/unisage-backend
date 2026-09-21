package com.unisage.backend.service.document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.unisage.backend.audit.Auditable;
import com.unisage.backend.dto.request.CreateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentStatusRequest;
import com.unisage.backend.dto.response.CitationDocumentResponse;
import com.unisage.backend.dto.response.DocumentResponse;
import com.unisage.backend.dto.response.DocumentVersionResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.AccessLevel;
import com.unisage.backend.entity.Category;
import com.unisage.backend.entity.Department;
import com.unisage.backend.entity.Document;
import com.unisage.backend.entity.DocumentVersion;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.DocStatus;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AccessLevelRepository;
import com.unisage.backend.repository.CategoryRepository;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.DocumentVersionRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.file.FileService;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final DepartmentRepository departmentRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final AccessLevelRepository accessLevelRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final SecurityUtil securityUtil;
    private final FileService fileService;

    @Override
    @Transactional
    public DocumentResponse createDocument(CreateDocumentRequest request) {
        UUID userId = securityUtil.getCurrentUserId();
        User uploader = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        AccessLevel minAccessLevel = resolveMinAccessLevel(request.minAccessLevelId(), uploader);

        Department docPackage = null;
        if (request.docPackageId() != null) {
            docPackage = departmentRepository.findById(request.docPackageId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));
        }

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
        }

        String sourceUrl = request.sourceUrl();
        if (request.file() != null && !request.file().isEmpty()) {
            sourceUrl = fileService.upload(request.file());
        }

        Document document = Document.builder()
                .docPackage(docPackage)
                .category(category)
                .title(request.title())
                .sourceUrl(sourceUrl)
                .fileType(request.fileType())
                .minAccessLevel(minAccessLevel)
                .status(DocStatus.PENDING)
                .isPublic(request.isPublic())
                .ingestedBy(uploader)
                .version(1)
                .build();

        document = documentRepository.save(document);
        return mapToResponse(document);
    }

    @Override
    @Transactional
    public DocumentResponse updateDocument(UUID id, UpdateDocumentRequest request) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        if (request.docPackageId() != null) {
            Department docPackage = departmentRepository.findById(request.docPackageId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));
            document.setDocPackage(docPackage);
        }

        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
            document.setCategory(category);
        }

        if (request.title() != null) {
            document.setTitle(request.title());
        }

        if (request.file() != null && !request.file().isEmpty()) {
            if (document.getSourceUrl() != null) {
                UUID userId = securityUtil.getCurrentUserId();
                User uploader = userRepository.findById(userId)
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

                DocumentVersion version = DocumentVersion.builder()
                        .document(document)
                        .versionNumber(document.getVersion())
                        .sourceUrl(document.getSourceUrl())
                        .fileType(document.getFileType())
                        .uploadedBy(uploader)
                        .createdAt(LocalDateTime.now())
                        .build();
                documentVersionRepository.save(version);

                document.setVersion(document.getVersion() + 1);
            }

            document.setSourceUrl(fileService.upload(request.file()));
        } else if (request.sourceUrl() != null) {
            document.setSourceUrl(request.sourceUrl());
        }

        if (request.fileType() != null) {
            document.setFileType(request.fileType());
        }

        if (request.minAccessLevelId() != null) {
            UUID userId = securityUtil.getCurrentUserId();
            User requester = userRepository.findById(userId)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            document.setMinAccessLevel(resolveMinAccessLevel(request.minAccessLevelId(), requester));
        }

        if (request.isPublic() != null) {
            document.setIsPublic(request.isPublic());
        }

        document = documentRepository.save(document);
        return mapToResponse(document);
    }

    @Override
    @Transactional
    public DocumentResponse updateStatus(UUID id, UpdateDocumentStatusRequest request) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        document.setStatus(request.status());
        document = documentRepository.save(document);
        return mapToResponse(document);
    }

    // No dedicated download endpoint exists in this codebase — the presigned download URL is
    // included right in the single-document detail response (resolveFileUrl(), via
    // mapToResponse()). This detail fetch is therefore treated as the download/view signal.
    // Deliberately NOT applied to resolveFileUrl()/mapToResponse() themselves — those are also
    // called from getAll()'s pagination path and would fire once per row per page, which is
    // noise, not a meaningful audit signal.
    @Override
    @Auditable(action = AuditAction.DOWNLOAD, resourceType = ResourceType.DOCUMENT)
    public DocumentResponse getById(UUID id) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        return mapToResponse(document);
    }

    @Override
    public CitationDocumentResponse getCitationById(UUID id) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        return CitationDocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .fileType(document.getFileType())
                .fileName(toDisplayFileName(document.getSourceUrl()))
                .fileUrl(resolveFileUrl(document))
                .build();
    }

    /** Object keys are stored as {@code <uuid>_<original name>}; show only the original name. */
    private static String toDisplayFileName(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String name = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        return name.replaceFirst("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}_", "");
    }

    @Override
    public PageResponse<List<DocumentResponse>> getAll(Pageable pageable) {
        Page<Document> page = documentRepository.findAllActive(pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    @Transactional
    public void softDelete(UUID id) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        document.setDeletedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    @Override
    public List<DocumentVersionResponse> getVersionHistory(UUID documentId) {
        documentRepository.findById(documentId)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        List<DocumentVersion> versions = documentVersionRepository
                .findByDocumentIdOrderByVersionNumberDesc(documentId);

        return versions.stream()
                .map(v -> DocumentVersionResponse.builder()
                        .id(v.getId())
                        .versionNumber(v.getVersionNumber())
                        .fileType(v.getFileType())
                        .fileName(toDisplayFileName(v.getSourceUrl()))
                        .fileUrl(fileService.getPresignedUrl(v.getSourceUrl()))
                        .uploadedByUserId(v.getUploadedBy() != null ? v.getUploadedBy().getId() : null)
                        .uploadedByName(v.getUploadedBy() != null ? v.getUploadedBy().getFullName() : null)
                        .createdAt(v.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Resolves the requested AccessLevel (if any) and enforces that the requester's own
     * access level clears it — shared by createDocument() and updateDocument().
     */
    private AccessLevel resolveMinAccessLevel(UUID minAccessLevelId, User requester) {
        AccessLevel minAccessLevel = null;
        if (minAccessLevelId != null) {
            minAccessLevel = accessLevelRepository.findById(minAccessLevelId)
                    .orElseThrow(() -> new AppException(ErrorCode.ACCESS_LEVEL_NOT_FOUND));
        }
        int requiredLevel = minAccessLevel != null ? minAccessLevel.getLevel() : 0;
        int effectiveLevel = requester.getAccessLevel() != null ? requester.getAccessLevel().getLevel() : 0;
        if (effectiveLevel < requiredLevel) {
            throw new AppException(ErrorCode.DOCUMENT_PERMISSION_FORBIDDEN);
        }
        return minAccessLevel;
    }

    private String resolveFileUrl(Document document) {
        if (document.getSourceUrl() == null) {
            return null;
        }
        if (Boolean.TRUE.equals(document.getIsPublic())) {
            return fileService.getPresignedUrl(document.getSourceUrl());
        }

        // UUID userId = securityUtil.getCurrentUserIdOrNull();
        // if (userId == null) {
        //     return null;
        // }

        // User requester = userRepository.findById(userId).orElse(null);
        // int effectiveLevel = requester != null && requester.getAccessLevel() != null
        //         ? requester.getAccessLevel().getLevel() : 0;
        // int required = document.getMinAccessLevel() != null ? document.getMinAccessLevel().getLevel() : 0;
        // if (effectiveLevel < required) {
        //     return null;
        // }

        return fileService.getPresignedUrl(document.getSourceUrl());
    }

    private DocumentResponse mapToResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .sourceUrl(document.getSourceUrl())
                .fileUrl(resolveFileUrl(document))
                .fileType(document.getFileType())
                .status(document.getStatus())
                .isPublic(document.getIsPublic())
                .minAccessLevelId(document.getMinAccessLevel() != null ? document.getMinAccessLevel().getId() : null)
                .minAccessLevel(document.getMinAccessLevel() != null ? document.getMinAccessLevel().getLevel() : null)
                .version(document.getVersion())
                .departmentId(document.getDocPackage() != null ? document.getDocPackage().getId() : null)
                .departmentName(document.getDocPackage() != null ? document.getDocPackage().getName() : null)
                .categoryId(document.getCategory() != null ? document.getCategory().getId() : null)
                .categoryName(document.getCategory() != null ? document.getCategory().getName() : null)
                .ingestedByUserId(document.getIngestedBy() != null ? document.getIngestedBy().getId() : null)
                .isActive(document.getIsActive())
                .deletedAt(document.getDeletedAt())
                .createdAt(document.getCreatedAt())
                .createdBy(document.getCreatedBy() != null ? document.getCreatedBy().getId().toString() : null)
                .createdByName(document.getCreatedBy() != null ? document.getCreatedBy().getFullName() : null)
                .updatedAt(document.getUpdatedAt())
                .updatedBy(document.getUpdatedBy() != null ? document.getUpdatedBy().getId().toString() : null)
                .updatedByName(document.getUpdatedBy() != null ? document.getUpdatedBy().getFullName() : null)
                .build();
    }
}
