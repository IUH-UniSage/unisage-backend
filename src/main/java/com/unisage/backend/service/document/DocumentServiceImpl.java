package com.unisage.backend.service.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.unisage.backend.dto.request.CreateDocumentRequest;
import com.unisage.backend.dto.request.UpdateDocumentRequest;
import com.unisage.backend.dto.response.DocumentResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.Category;
import com.unisage.backend.entity.Department;
import com.unisage.backend.entity.Document;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.DocStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.CategoryRepository;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.UserRepository;
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
    private final SecurityUtil securityUtil;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    @Transactional
    public DocumentResponse createDocument(CreateDocumentRequest request) {
        UUID userId = securityUtil.getCurrentUserId();
        User uploader = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Integer minAccessLevel = request.minAccessLevel() != null ? request.minAccessLevel() : 0;
        Integer maxAccessLevel = userRepository.findMaxAccessLevelByUserId(userId);
        int effectiveMax = maxAccessLevel != null ? maxAccessLevel : 0;
        if (effectiveMax < minAccessLevel) {
            throw new AppException(ErrorCode.DOCUMENT_PERMISSION_FORBIDDEN);
        }

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
            sourceUrl = storeFile(request.file());
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
            document.setSourceUrl(storeFile(request.file()));
        } else if (request.sourceUrl() != null) {
            document.setSourceUrl(request.sourceUrl());
        }

        if (request.fileType() != null) {
            document.setFileType(request.fileType());
        }

        if (request.minAccessLevel() != null) {
            UUID userId = securityUtil.getCurrentUserId();
            Integer maxAccessLevel = userRepository.findMaxAccessLevelByUserId(userId);
            int effectiveMax = maxAccessLevel != null ? maxAccessLevel : 0;
            if (effectiveMax < request.minAccessLevel()) {
                throw new AppException(ErrorCode.DOCUMENT_PERMISSION_FORBIDDEN);
            }
            document.setMinAccessLevel(request.minAccessLevel());
        }

        if (request.isPublic() != null) {
            document.setIsPublic(request.isPublic());
        }

        document = documentRepository.save(document);
        return mapToResponse(document);
    }

    @Override
    public DocumentResponse getById(UUID id) {
        Document document = documentRepository.findById(id)
                .filter(d -> d.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        return mapToResponse(document);
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

    private String storeFile(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            String storedFilename = UUID.randomUUID() + "_" + originalFilename;
            Path targetPath = uploadPath.resolve(storedFilename);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return storedFilename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    private DocumentResponse mapToResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .title(document.getTitle())
                .sourceUrl(document.getSourceUrl())
                .fileType(document.getFileType())
                .status(document.getStatus())
                .isPublic(document.getIsPublic())
                .minAccessLevel(document.getMinAccessLevel())
                .version(document.getVersion())
                .departmentId(document.getDocPackage() != null ? document.getDocPackage().getId() : null)
                .departmentName(document.getDocPackage() != null ? document.getDocPackage().getName() : null)
                .categoryId(document.getCategory() != null ? document.getCategory().getId() : null)
                .categoryName(document.getCategory() != null ? document.getCategory().getName() : null)
                .ingestedByUserId(document.getIngestedBy() != null ? document.getIngestedBy().getId() : null)
                .isActive(document.getIsActive())
                .deletedAt(document.getDeletedAt())
                .createdAt(document.getCreatedAt())
                .createdBy(document.getCreatedBy())
                .updatedAt(document.getUpdatedAt())
                .updatedBy(document.getUpdatedBy())
                .build();
    }
}
