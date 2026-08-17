package com.unisage.backend.service.file;

import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /** Uploads the file to MinIO and returns the stored object key (not a URL). */
    String upload(MultipartFile file);

    /** Deletes an object by key. No-op if the key is null/blank. */
    void delete(String objectKey);

    /** Generates a time-limited, read-only presigned URL for an object key. */
    String getPresignedUrl(String objectKey);
}
