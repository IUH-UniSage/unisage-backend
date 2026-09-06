package com.unisage.backend.security;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.predefined.PredefinedPublicPaths.PublicPath;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gates the handful of endpoints that {@code unisage-agent} (Python) calls directly on this
 * service, bypassing the gateway — {@code PATCH /messages/*} — behind a shared secret header,
 * {@code X-Internal-Secret}, mirroring the same pattern already used between api-gateway and
 * unisage-agent.
 *
 * <p>On every request, regardless of path, a valid secret also marks the request as a trusted
 * server-to-server call via {@link #TRUSTED_INTERNAL_CALLER_ATTRIBUTE} — this is the single place
 * that decides "is this really Python calling", so IP-resolution code (see
 * {@code MessageController#extractClientIp}) can safely trust a caller-supplied
 * {@code X-Forwarded-For} only when this attribute is set, instead of duplicating the secret
 * check itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InternalSecretFilter extends OncePerRequestFilter {

    /** Request attribute set once a valid {@code X-Internal-Secret} has been verified for this call. */
    public static final String TRUSTED_INTERNAL_CALLER_ATTRIBUTE = "internalSecretVerified";

    private static final String HEADER_NAME = "X-Internal-Secret";

    /** Paths only ever meant to be called by trusted internal services, never an end-user. */
    private static final List<PublicPath> INTERNAL_ONLY_PATHS = List.of(
            new PublicPath("PATCH", "/messages/*")
    );

    private final ObjectMapper objectMapper;

    @Value("${app.internal.secret-key}")
    private String internalSecretKey;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        boolean validSecret = isValidSecret(request.getHeader(HEADER_NAME));
        if (validSecret) {
            request.setAttribute(TRUSTED_INTERNAL_CALLER_ATTRIBUTE, Boolean.TRUE);
        }

        String normalizedPath = normalize(request.getRequestURI());
        if (!validSecret && isInternalOnlyPath(normalizedPath, request.getMethod())) {
            log.warn("Missing/invalid X-Internal-Secret for internal-only path: {} {}",
                    request.getMethod(), normalizedPath);
            respondForbidden(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isValidSecret(String headerValue) {
        return internalSecretKey != null && internalSecretKey.equals(headerValue);
    }

    private String normalize(String requestUri) {
        return requestUri.replace(contextPath, "");
    }

    private boolean isInternalOnlyPath(String path, String method) {
        return INTERNAL_ONLY_PATHS.stream().anyMatch(p ->
                ("*".equals(p.method()) || p.method().equalsIgnoreCase(method))
                        && pathMatcher.match(p.pattern(), path));
    }

    private void respondForbidden(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.INTERNAL_SECRET_INVALID;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(errorCode.getCode(), errorCode.getMessage(), null)));
    }
}
