package com.unisage.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiResponse<T>(int code, String message, T data, Map<String, String> errors) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(1000, "Successful", data, null);
    }

    public static <T> ApiResponse<T> successWithoutData() {
        return new ApiResponse<>(1000, "Successful", null, null);
    }

    public static <T> ApiResponse<T> error(int code, String message, Map<String, String> errors) {
        return new ApiResponse<>(code, message, null, errors);
    }
}
