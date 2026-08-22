package com.unisage.backend.service.file;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;

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

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${minio.presigned-url-expiry-seconds}")
    private int expirySeconds;

    @Override
    public String upload(MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "file";
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
