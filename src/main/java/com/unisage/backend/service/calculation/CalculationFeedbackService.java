package com.unisage.backend.service.calculation;

import java.util.UUID;

import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.dto.response.CalculationFeedbackResponse;

public interface CalculationFeedbackService {

    /**
     * Records the caller's verdict on one AI-computed (mode llm) calculation item of their own assistant
     * message, and for a signed-in caller files/updates/closes that item's support ticket.
     * {@code guestSessionToken} is the raw cookie/header value; null for authenticated callers.
     */
    default CalculationFeedbackResponse submit(UUID messageId, CalculationFeedbackRequest request,
                                               UUID callerId, String guestSessionToken) {
        return submit(messageId, request, callerId, guestSessionToken, 0L);
    }

    /**
     * Same, also rejecting a request body over the 2 KB limit ({@code requestBytes} is the HTTP
     * Content-Length; 0 or negative when unknown).
     */
    CalculationFeedbackResponse submit(UUID messageId, CalculationFeedbackRequest request,
                                       UUID callerId, String guestSessionToken, long requestBytes);
}
