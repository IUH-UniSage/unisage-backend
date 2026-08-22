package com.unisage.backend.service.file;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FileServiceImplTest {

    private MinioClient minioClient;
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        MinioClient publicMinioClient = mock(MinioClient.class);
        fileService = new FileServiceImpl(minioClient, publicMinioClient);
        ReflectionTestUtils.setField(fileService, "bucket", "unisage-documents");
    }

    @Test
    void upload_allowsWhitelistedType() throws Exception {
        MultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "content".getBytes());

        String objectKey = fileService.upload(file);

        assertThat(objectKey).endsWith("_report.pdf");
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void upload_rejectsExtensionNotInWhitelist() throws Exception {
        MultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "content".getBytes());

        assertThatThrownBy(() -> fileService.upload(file))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);

        verify(minioClient, never()).putObject(any(PutObjectArgs.class));
    }
}
