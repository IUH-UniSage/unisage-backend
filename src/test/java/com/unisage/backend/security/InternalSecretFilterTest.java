package com.unisage.backend.security;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalSecretFilterTest {

    private static final String SECRET = "unisage-internal-secret-key-2026";

    private InternalSecretFilter filter;
    private FilterChain filterChain;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        filter = new InternalSecretFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalSecretKey", SECRET);
        ReflectionTestUtils.setField(filter, "contextPath", "/api/v1");

        filterChain = mock(FilterChain.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private HttpServletRequest requestFor(String method, String uri, String secretHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn("/api/v1" + uri);
        when(request.getHeader("X-Internal-Secret")).thenReturn(secretHeader);
        return request;
    }

    // ── PATCH /messages/{id} is internal-only: enforced ─────────────────────

    @Test
    void patchMessage_missingSecret_blockedWithForbidden() throws Exception {
        HttpServletRequest request = requestFor("PATCH", "/messages/abc-123", null);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(403);
        assertThat(responseBody.toString()).contains("Internal-Secret");
    }

    @Test
    void patchMessage_wrongSecret_blockedWithForbidden() throws Exception {
        HttpServletRequest request = requestFor("PATCH", "/messages/abc-123", "not-the-secret");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(403);
    }

    @Test
    void patchMessage_correctSecret_proceedsAndMarksTrusted() throws Exception {
        HttpServletRequest request = requestFor("PATCH", "/messages/abc-123", SECRET);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(request).setAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE, Boolean.TRUE);
        verify(response, never()).setStatus(403);
    }

    // ── POST /messages is a public path, not internal-only: never blocked ──

    @Test
    void postMessages_missingSecret_stillProceeds_notMarkedTrusted() throws Exception {
        HttpServletRequest request = requestFor("POST", "/messages", null);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(request, never()).setAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE, Boolean.TRUE);
    }

    @Test
    void postMessages_correctSecret_proceedsAndMarksTrusted() throws Exception {
        HttpServletRequest request = requestFor("POST", "/messages", SECRET);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(request).setAttribute(InternalSecretFilter.TRUSTED_INTERNAL_CALLER_ATTRIBUTE, Boolean.TRUE);
    }
}
