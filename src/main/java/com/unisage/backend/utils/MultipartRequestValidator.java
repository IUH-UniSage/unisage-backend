package com.unisage.backend.utils;

import org.springframework.stereotype.Component;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MultipartRequestValidator {

    public void validateSingleFile(HttpServletRequest request, String fieldName) {
        try {
            long fileParts = request.getParts().stream()
                    .filter(part -> fieldName.equals(part.getName()))
                    .count();
            if (fileParts > 1) {
                throw new AppException(ErrorCode.FILE_TOO_MANY_FILES);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Could not inspect multipart parts for field '{}': {}", fieldName, e.getMessage());
        }
    }
}
