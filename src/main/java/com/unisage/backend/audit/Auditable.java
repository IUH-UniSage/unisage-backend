package com.unisage.backend.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;

/**
 * Marks a service-layer method whose successful execution is itself a security/compliance-
 * relevant event — reading (not writing) sensitive data, such as fetching a single document's
 * detail (which includes its presigned download link) or a single user's PII. This is the AOP
 * half of the UNISAGE-60 hybrid extension: the Hibernate listener in {@link AuditEventListener}
 * only sees DB writes, so it cannot see a pure read/export path — see
 * docs/adr/0003-audit-log-persistence.md, section "Update — hybrid extension".
 *
 * <p><b>Contract for annotated methods</b> (enforced by {@link AuditableAspect}, not by the
 * compiler — read this before adding {@code @Auditable} to a new method):
 * <ul>
 *   <li>The method's <b>first parameter</b> must be a {@link java.util.UUID} or {@link String} —
 *       this is taken as the audited {@code resourceId}, exactly as passed in, no reflection into
 *       the return value.</li>
 *   <li>Apply this ONLY to a single-item detail/read method (e.g. {@code getById(UUID id)}).
 *       NEVER apply it to a list/pagination/bulk method — that would fire once per row (or once
 *       per page) and flood the audit trail with noise instead of a meaningful signal.</li>
 *   <li>The aspect runs {@code @AfterReturning} — an audit row is written only when the method
 *       returns normally. A failed/forbidden access attempt (exception thrown, e.g. not-found or
 *       permission-denied) is deliberately NOT audited here: this annotation records what was
 *       actually viewed/exported, not what was attempted. If failed-access attempts ever need
 *       their own audit trail, that is a separate, explicit concern (e.g. an authorization filter
 *       audit), not a job for this annotation.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    AuditAction action();

    ResourceType resourceType();
}
