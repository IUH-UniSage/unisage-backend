package com.unisage.backend.security;

import com.unisage.backend.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String jwtSecret;

    private Key key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Value("${jwt.access-token.expiration}")
    private long accessTokenExpiration;

    @Value("${jwt.refresh-token.expiration}")
    private long refreshTokenExpiration;

    // Generate access token
    public String generateAccessToken(User user, List<Map<String, Object>> departmentAccess, List<String> permissions) {
        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("code", user.getCode())
                .claim("role", user.getRole().getName())
                .claim("type", "access")
                .claim("department_access", departmentAccess)
                .claim("permissions", permissions)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(key)
                .compact();
    }

    // Generate refresh token
    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("type", "refresh")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshTokenExpiration))
                .signWith(key)
                .compact();
    }

    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    public String getRole(String token) {
        return (String) parseClaims(token).get("role");
    }
    public String getCode(String token) {
        return (String) parseClaims(token).get("code");
    }

    public String getTokenType(String token) {
        return (String) parseClaims(token).get("type");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDepartmentAccess(String token) {
        List<?> raw = parseClaims(token).get("department_access", List.class);
        return raw == null ? null : (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(String token) {
        List<?> raw = parseClaims(token).get("permissions", List.class);
        return raw == null ? null : (List<String>) raw;
    }

    public boolean isRefreshToken(String token) {
        try {
            return "refresh".equals(getTokenType(token));
        } catch (Exception e) {
            return false;
        }
    }

    public long getRefreshExpirationMs() {
        return refreshTokenExpiration;
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                   .setSigningKey(key)
                   .build()
                   .parseClaimsJws(token)
                   .getBody();
    }

    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}