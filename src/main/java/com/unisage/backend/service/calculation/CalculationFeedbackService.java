package com.unisage.backend.service.calculation;

import java.util.UUID;

import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.response.CalculationFeedbackResponse;

public interface CalculationFeedbackService {

    /**
     * Records the caller's verdict on one retrieved-formula calculation item of their own assistant
     * message, and for a signed-in caller files/updates/closes that item's support ticket.
     * {@code guestSessionToken} is the raw cookie/header value; null for authenticated callers.
     */
    CalculationFeedbackResponse submit(UUID messageId, CalculationFeedbackRequest request,
                                       UUID callerId, String guestSessionToken);
}
