package com.unisage.backend.audit;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.audit.event.LoginFailedEvent;
import com.unisage.backend.audit.event.LoginSucceededEvent;
import com.unisage.backend.audit.event.LogoutEvent;
import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns login/logout domain events into {@link AuditLog} rows. Unlike {@link AuditEventListener}
 * (a raw Hibernate listener, instantiated by Hibernate outside the Spring container), this is a
 * normal {@code @Component}/{@code @EventListener} bean, so it can constructor-inject
 * {@link AuditLogWriter} directly — no {@link SpringContextHolder} indirection needed here.
 *
 * Kept synchronous (no {@code @Async}): login/logout is low-volume, so there is no need for the
 * extra complexity of an async event path. See docs/adr/0003-audit-log-persistence.md, section
 * "Update — hybrid extension".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthAuditListener {

    private final AuditLogWriter auditLogWriter;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onLoginSucceeded(LoginSucceededEvent event) {
        AuditLog entry = AuditLog.builder()
                .actorId(event.userId())
                .actorCode(event.code())
                .action(AuditAction.LOGIN)
                .resourceType(ResourceType.USER)
                .resourceId(event.userId() != null ? event.userId().toString() : null)
                .details(toJson(Map.of("code", String.valueOf(event.code()))))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogWriter.persist(entry);
    }

    @EventListener
    public void onLoginFailed(LoginFailedEvent event) {
        // No authenticated principal, and possibly no real User row at all (mistyped/nonexistent
        // code) — actorId/resourceId are null-safely left null, and the attempted code is
        // recorded in details instead. Never log the raw password.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("attemptedCode", event.attemptedCode());
        details.put("reason", event.reason());

        AuditLog entry = AuditLog.builder()
                .action(AuditAction.LOGIN_FAILED)
                .resourceType(ResourceType.USER)
                .details(toJson(details))
                .createdAt(LocalDateTime.now())
                .build();
        auditLogWriter.persist(entry);
    }

    @EventListener
    public void onLogout(LogoutEvent event) {
        AuditLog entry = AuditLog.builder()
                .actorId(event.userId())
                .actorCode(event.code())
                .action(AuditAction.LOGOUT)
                .resourceType(ResourceType.USER)
                .resourceId(event.userId() != null ? event.userId().toString() : null)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogWriter.persist(entry);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("Failed to serialize auth audit details: {}", e.getMessage());
            return String.valueOf(details);
        }
    }
}
