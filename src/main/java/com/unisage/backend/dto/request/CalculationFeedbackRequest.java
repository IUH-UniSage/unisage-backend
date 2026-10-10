package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.entity.enums.CalculationWrongReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * {@code POST /messages/{messageId}/calculation-feedback} body (contracts/chat-sse.md §5b). The
 * cross-field rules - {@code reason} required iff WRONG, {@code note} required for OTHER - are
 * checked in {@code CalculationFeedbackServiceImpl}.
 */
@Builder
public record CalculationFeedbackRequest(
    @NotBlank(message = "itemId không được để trống")
    @Pattern(regexp = "^T[1-3]$", message = "itemId phải là T1, T2 hoặc T3")
    String itemId,

    @NotNull(message = "verdict không được để trống")
    CalculationVerdict verdict,

    CalculationWrongReason reason,

    // Only ever stored in the ticket, never in message metadata.
    @Size(max = 500, message = "note không được vượt quá 500 ký tự")
    String note
) {}
