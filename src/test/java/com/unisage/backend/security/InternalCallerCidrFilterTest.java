package com.unisage.backend.security;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

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

class InternalCallerCidrFilterTest {

    private InternalCallerCidrFilter filter;
    private FilterChain filterChain;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        filter = new InternalCallerCidrFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "contextPath", "/api/v1");
        ReflectionTestUtils.setField(filter, "allowedCidrsRaw", "172.30.0.0/24");

        filterChain = mock(FilterChain.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private HttpServletRequest requestFor(String uri, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1" + uri);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    void nonInternalPath_neverChecked() throws Exception {
        HttpServletRequest request = requestFor("/chat-models", "1.2.3.4");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(403);
    }

    @Test
    void internalPath_remoteAddrInCidr_proceeds() throws Exception {
        HttpServletRequest request = requestFor("/internal/model-registry/version", "172.30.0.5");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void internalPath_remoteAddrOutsideCidr_forbidden() throws Exception {
        HttpServletRequest request = requestFor("/internal/model-registry/version", "8.8.8.8");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(403);
    }

    @Test
    void internalPath_emptyCidrList_failsClosed() throws Exception {
        ReflectionTestUtils.setField(filter, "allowedCidrsRaw", "");
        HttpServletRequest request = requestFor("/internal/model-registry/version", "172.30.0.5");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(403);
    }

    @Test
    void internalPath_xForwardedForIgnored() throws Exception {
        HttpServletRequest request = requestFor("/internal/model-registry/version", "8.8.8.8");
        when(request.getHeader("X-Forwarded-For")).thenReturn("172.30.0.5");

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assertThat(responseBody.toString()).isNotEmpty();
    }

    @Test
    void sourceNeverReadsForwardedHeaders() throws IOException {
        Path source = Path.of("src/main/java/com/unisage/backend/security/InternalCallerCidrFilter.java");
        String content = Files.readString(source);

        assertThat(content)
                .doesNotContain("X-Forwarded-For")
                .doesNotContain("\"Forwarded\"")
                .doesNotContain("X-Real-IP")
                .doesNotContain("getHeader(");
    }
}
