package com.unisage.backend.service.file;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.unisage.backend.entity.enums.AllowedFileType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Http.Method;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {

    private final MinioClient minioClient;

    /**
     * Field name intentionally matches the "publicMinioClient" @Bean method name in
     * MinioConfig — with two MinioClient beans present, Spring's constructor autowiring
     * falls back to matching the parameter name against the bean name to disambiguate.
     */
    private final MinioClient publicMinioClient;
    private final SystemConfigResolver configResolver;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.presigned-url-expiry-seconds}")
    private int expirySeconds;

    // Fallback whitelist when the config row is missing/corrupted - AllowedFileType is no longer
    // the source of truth at runtime (ingest.allowed_file_extensions in System Settings is), but
    // its values still document/seed the intended default set.
    private static final List<String> DEFAULT_ALLOWED_EXTENSIONS = Arrays.stream(AllowedFileType.values())
            .map(AllowedFileType::getExtension)
            .toList();

    @Override
    public String upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "file";
        validateFileType(originalFilename);
        validateFileSize(file);
        String objectKey = UUID.randomUUID() + "_" + originalFilename;

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1L)
                    .contentType(file.getContentType())
                    .build());
            return objectKey;
        } catch (Exception e) {
            log.error("MinIO upload failed for {}", originalFilename, e);
            throw new AppException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    private void validateFileType(String originalFilename) {
        List<String> allowedExtensions = configResolver.getStringList(
                "ingest.allowed_file_extensions", DEFAULT_ALLOWED_EXTENSIONS);
        String extension = extractExtension(originalFilename);
        if (extension == null || !allowedExtensions.contains(extension)) {
            log.warn("Rejected upload of '{}': not in the allowed file type whitelist", originalFilename);
            throw new AppException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
    }

    private String extractExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String lower = filename.toLowerCase();
        int dotIndex = lower.lastIndexOf('.');
        return (dotIndex < 0 || dotIndex == lower.length() - 1) ? null : lower.substring(dotIndex);
    }

    private void validateFileSize(MultipartFile file) {
        int maxSizeMb = configResolver.getInt("ingest.max_file_size_mb", 10);
        long maxSizeBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            log.warn("Rejected upload of size {} bytes: exceeds {} MB limit", file.getSize(), maxSizeMb);
            throw new AppException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("MinIO delete failed for {}", objectKey, e);
            throw new AppException(ErrorCode.FILE_DELETE_FAILED);
        }
    }

    @Override
    public String getPresignedUrl(String objectKey) {
        try {
            return publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (ErrorResponseException e) {
            throw new AppException(ErrorCode.FILE_NOT_FOUND);
        } catch (Exception e) {
            log.error("MinIO presign failed for {}", objectKey, e);
            throw new AppException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }
}
