package com.unisage.backend.dto.request.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /internal/calculation-traces} body - every calculation item of one assistant message,
 * sent once per turn by unisage-agent before its finalize PATCH (SPEC-calculation-node §7.2(b)).
 * The 16 KB-per-trace limit is checked in the service on the serialized JSON.
 */
public record CalculationTraceIngestRequest(
    @NotNull(message = "messageId không được để trống")
    UUID messageId,

    @NotNull(message = "items không được để trống")
    @Size(min = 1, max = 3, message = "items phải có từ 1 đến 3 phần tử")
    List<@Valid @NotNull Item> items
) {
    public record Item(
        @NotBlank(message = "itemId không được để trống")
        @Pattern(regexp = "^T[1-3]$", message = "itemId phải là T1, T2 hoặc T3")
        String itemId,

        @Size(max = 100, message = "runId không được vượt quá 100 ký tự")
        String runId,

        @NotNull(message = "trace không được để trống")
        Map<String, Object> trace
    ) {}
}
