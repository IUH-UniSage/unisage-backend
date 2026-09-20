package com.unisage.backend.audit;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.security.UserPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implements the {@link Auditable} contract: fires {@code @AfterReturning} (deliberately, not
 * {@code @Around}) so a failed/forbidden call never produces an audit row — see the Javadoc on
 * {@link Auditable} for why. Runs inline/synchronously; unlike {@link AuditEventListener} this is
 * never invoked from inside a Hibernate flush callback, and a read/export path is not part of a
 * mutating business transaction whose rollback would invalidate the audit's meaning, so no
 * {@code TransactionSynchronization.afterCommit()} hand-off is needed here.
 *
 * A normal {@code @Component}/{@code @Aspect} Spring bean (like {@link AuthAuditListener}), so
 * {@link AuditLogWriter} is constructor-injected directly.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditableAspect {

    private final AuditLogWriter auditLogWriter;

    @AfterReturning("@annotation(auditable)")
    public void audit(JoinPoint joinPoint, Auditable auditable) {
        try {
            String resourceId = extractResourceId(joinPoint);

            UUID actorId = null;
            String actorCode = null;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof UserPrincipal principal) {
                actorId = principal.getUserId();
                actorCode = principal.getCode();
            }

            AuditLog entry = AuditLog.builder()
                    .actorId(actorId)
                    .actorCode(actorCode)
                    .action(auditable.action())
                    .resourceType(auditable.resourceType())
                    .resourceId(resourceId)
                    .createdAt(LocalDateTime.now())
                    .build();

            auditLogWriter.persist(entry);
        } catch (Exception e) {
            // Same rule as everywhere else in the audit subsystem: auditing must never break the
            // business call it is observing.
            log.warn("Failed to build audit entry for {}: {}", joinPoint.getSignature(), e.getMessage(), e);
        }
    }

    /**
     * Contract (see {@link Auditable}): the audited method's first parameter must be a
     * {@link UUID} or {@link String}, taken as-is as the resourceId.
     */
    private String extractResourceId(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length == 0) {
            return null;
        }
        Object first = args[0];
        if (first instanceof UUID uuid) {
            return uuid.toString();
        }
        if (first instanceof String str) {
            return str;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        log.warn("@Auditable method {} first argument is not a UUID/String (was {}) — "
                        + "resourceId will be recorded as null; fix the annotated method's contract.",
                method, first == null ? "null" : first.getClass());
        return null;
    }
}
