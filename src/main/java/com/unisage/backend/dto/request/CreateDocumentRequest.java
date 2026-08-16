package com.unisage.backend.dto.request;

import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import lombok.Builder;

@Builder
public record CreateDocumentRequest(
    UUID docPackageId,
    UUID categoryId,
    String title,
    String sourceUrl,
    String fileType,
    Integer minAccessLevel,
    Boolean isPublic,
    MultipartFile file
) {}
