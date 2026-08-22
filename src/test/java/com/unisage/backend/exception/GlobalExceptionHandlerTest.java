package com.unisage.backend.exception;

import com.unisage.backend.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void maxUploadSizeExceeded_returnsClearBadRequest_insteadOfGenericServerError() {
        ReflectionTestUtils.setField(handler, "maxFileSize", "10MB");

        ResponseEntity<ApiResponse<Map<String, String>>> response =
                handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(10_485_760L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED.getCode());
        assertThat(response.getBody().message()).contains("10MB");
    }

    @Test
    void uncategorizedException_stillFallsBackToGenericServerError() {
        ResponseEntity<ApiResponse<Map<String, String>>> response =
                handler.handleUncategorizedException(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SYS_UNCATEGORIZED.getCode());
    }
}
