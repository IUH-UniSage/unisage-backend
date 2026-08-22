package com.unisage.backend.utils;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultipartRequestValidatorTest {

    private final MultipartRequestValidator validator = new MultipartRequestValidator();

    @Test
    void allowsExactlyOneFilePart() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Part filePart = partNamed("file");
        Part titlePart = partNamed("title");
        when(request.getParts()).thenReturn(List.of(filePart, titlePart));

        assertThatCode(() -> validator.validateSingleFile(request, "file")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMoreThanOneFilePart() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Part firstFilePart = partNamed("file");
        Part secondFilePart = partNamed("file");
        when(request.getParts()).thenReturn(List.of(firstFilePart, secondFilePart));

        assertThatThrownBy(() -> validator.validateSingleFile(request, "file"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TOO_MANY_FILES);
    }

    @Test
    void doesNothingWhenPartsCannotBeInspected() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParts()).thenThrow(new RuntimeException("not a multipart request"));

        assertThatCode(() -> validator.validateSingleFile(request, "file")).doesNotThrowAnyException();
    }

    private Part partNamed(String name) {
        Part part = mock(Part.class);
        when(part.getName()).thenReturn(name);
        return part;
    }
}
