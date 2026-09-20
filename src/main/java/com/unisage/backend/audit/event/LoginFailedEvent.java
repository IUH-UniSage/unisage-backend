package com.unisage.backend.audit.event;

/**
 * Published by {@code AuthServiceImpl.login(...)} when authentication fails — either the code
 * does not exist, or the password does not match. There is no authenticated {@code UserPrincipal}
 * (and possibly no real {@code User} row at all, for a mistyped/nonexistent code) at this point,
 * so only the attempted {@code code} the caller typed is captured — never the raw password.
 *
 * See docs/adr/0003-audit-log-persistence.md, section "Update — hybrid extension".
 */
public record LoginFailedEvent(String attemptedCode, String reason) {
}
