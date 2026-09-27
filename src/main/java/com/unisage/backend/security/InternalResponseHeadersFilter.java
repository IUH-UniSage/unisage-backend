package com.unisage.backend.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Attaches {@code Cache-Control: no-store} + {@code Pragma: no-cache} to every response under
 * {@code /internal/**} (including errors) — some endpoints there return plaintext API keys.
 */
@Component
public class InternalResponseHeadersFilter extends OncePerRequestFilter {

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String normalizedPath = request.getRequestURI().replace(contextPath, "");
        if (pathMatcher.match("/internal/**", normalizedPath)) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }

        filterChain.doFilter(request, response);
    }
}
