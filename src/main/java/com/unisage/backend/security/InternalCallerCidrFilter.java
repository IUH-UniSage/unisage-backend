package com.unisage.backend.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.exception.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Second layer guarding {@code /internal/**}: caller's socket address must be in
 * {@code INTERNAL_ALLOWED_CIDRS}. Fail-closed on empty/unparsable list; reads only
 * {@link HttpServletRequest#getRemoteAddr()}, never a forwarded-for header.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalCallerCidrFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${app.internal.allowed-cidrs:}")
    private String allowedCidrsRaw;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String normalizedPath = normalize(request.getRequestURI());
        if (!isInternalPath(normalizedPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        CidrMatcher matcher = CidrMatcher.parse(allowedCidrsRaw);
        String remoteAddr = request.getRemoteAddr();
        if (matcher.isEmpty() || !matcher.matches(remoteAddr)) {
            log.warn("Rejected /internal/** call from disallowed remoteAddr={} path={}", remoteAddr, normalizedPath);
            respondForbidden(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String normalize(String requestUri) {
        return requestUri.replace(contextPath, "");
    }

    private boolean isInternalPath(String path) {
        return pathMatcher.match("/internal/**", path);
    }

    private void respondForbidden(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.INTERNAL_CALLER_NOT_ALLOWED;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(errorCode.getCode(), errorCode.getMessage(), null)));
    }
}
