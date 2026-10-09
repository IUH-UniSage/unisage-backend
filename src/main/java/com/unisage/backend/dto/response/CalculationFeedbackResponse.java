package com.unisage.backend.dto.response;

import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.entity.enums.CalculationWrongReason;

import lombok.Builder;

/**
 * {@code ticketCreated} is true when this WRONG verdict filed or updated the item's support ticket;
 * always false for CORRECT and for guests (a ticket needs a user).
 */
@Builder
public record CalculationFeedbackResponse(
    String itemId,
    CalculationVerdict verdict,
    CalculationWrongReason reason,
    boolean ticketCreated
) {}
