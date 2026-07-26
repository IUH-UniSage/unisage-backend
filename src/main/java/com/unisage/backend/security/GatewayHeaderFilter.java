package com.unisage.backend.security;

import com.unisage.backend.utils.CookieUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GatewayHeaderFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtUtil.validate(token) && "access".equals(jwtUtil.getTokenType(token))) {
            try {
                UUID userId = UUID.fromString(jwtUtil.getUserId(token));
                String role = jwtUtil.getRole(token);
                String code = jwtUtil.getCode(token);

                var authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER"))
                );
                
                UserPrincipal principal = UserPrincipal.builder()
                        .userId(userId)
                        .role(role)
                        .code(code)
                        .authorities(authorities)
                        .build();

                var auth = new GatewayAuthenticationToken(principal, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (Exception e) {
                logger.error("Could not set authentication: " + e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return cookieUtil.extractAccessTokenFromCookie(request);
    }
}