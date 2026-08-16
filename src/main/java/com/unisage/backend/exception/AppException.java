package com.unisage.backend.exception;

import java.util.Map;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> errors;

    public AppException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public AppException(ErrorCode errorCode, Map<String, String> errors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.errors = errors;
    }
}
