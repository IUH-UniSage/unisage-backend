package com.unisage.backend.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.request.CalculationFeedbackRequest;
import com.unisage.backend.entity.enums.CalculationVerdict;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.security.InternalSecretFilter;
import com.unisage.backend.service.calculation.CalculationFeedbackService;
import com.unisage.backend.service.conversation.MessageService;
import com.unisage.backend.utils.CookieUtil;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageControllerTest {

    private final CookieUtil cookieUtil = mock(CookieUtil.class);
    private final MessageController controller =
            new MessageController(mock(MessageService.class), mock(CalculationFeedbackService.class),
                    mock(SecurityUtil.class), cookieUtil);

    private String extractGuestSessionToken(HttpServletRequest request) {
        return ReflectionTestUtils.invokeMethod(controller, "extractGuestSessionToken", request);
    }

    @Test
    void trustedInternalCaller_withHeader_prefersForwardedToken() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(Boolean.TRUE);
        when(request.getHeader("X-Guest-Session-Token")).thenReturn("forwarded-token");
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn(null); // agent has no cookie jar

        assertThat(extractGuestSessionToken(request)).isEqualTo("forwarded-token");
    }

    @Test
    void trustedInternalCaller_withoutHeader_fallsBackToCookie() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(Boolean.TRUE);
        when(request.getHeader("X-Guest-Session-Token")).thenReturn(null);
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn("cookie-token");

        assertThat(extractGuestSessionToken(request)).isEqualTo("cookie-token");
    }

    @Test
    void untrustedCaller_headerIsIgnored_cookieIsUsedInstead() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(null); // no valid X-Internal-Secret on this request
        when(request.getHeader("X-Guest-Session-Token")).thenReturn("spoofed-token");
        when(cookieUtil.extractGuestSessionTokenFromCookie(request)).thenReturn("real-cookie-token");

        assertThat(extractGuestSessionToken(request)).isEqualTo("real-cookie-token");
    }

    @Test
    void calculationFeedback_bodyOver2Kb_isRejectedBeforeTheService() {
        CalculationFeedbackService feedbackService = mock(CalculationFeedbackService.class);
        MessageController feedbackController = new MessageController(
                mock(MessageService.class), feedbackService, mock(SecurityUtil.class), cookieUtil);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(2049L);
        CalculationFeedbackRequest body = CalculationFeedbackRequest.builder()
                .itemId("T1").verdict(CalculationVerdict.CORRECT).build();

        assertThatThrownBy(() -> feedbackController.calculationFeedback(UUID.randomUUID(), body, request))
                .isInstanceOfSatisfying(AppException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CALCULATION_FEEDBACK_INVALID));
        verifyNoInteractions(feedbackService);
    }
}
