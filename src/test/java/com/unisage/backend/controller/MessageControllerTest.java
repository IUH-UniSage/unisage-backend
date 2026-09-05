package com.unisage.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.security.InternalSecretFilter;
import com.unisage.backend.service.conversation.MessageService;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageControllerTest {

    private final MessageController controller =
            new MessageController(mock(MessageService.class), mock(SecurityUtil.class));

    private String extractClientIp(HttpServletRequest request) {
        return ReflectionTestUtils.invokeMethod(controller, "extractClientIp", request);
    }

    @Test
    void trustedInternalCaller_withForwardedFor_prefersForwardedIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(Boolean.TRUE);
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9, 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1"); // Python's own address

        assertThat(extractClientIp(request)).isEqualTo("9.9.9.9");
    }

    @Test
    void trustedInternalCaller_withoutForwardedFor_fallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(Boolean.TRUE);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertThat(extractClientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void untrustedCaller_forwardedForHeaderIsIgnored_toPreventIpSpoofing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE))
                .thenReturn(null); // no valid X-Internal-Secret on this request
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9"); // spoofed by attacker
        when(request.getRemoteAddr()).thenReturn("203.0.113.5"); // attacker's real socket address

        assertThat(extractClientIp(request)).isEqualTo("203.0.113.5");
    }
}
