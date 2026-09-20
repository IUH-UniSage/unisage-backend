package com.unisage.backend.audit.event;

import java.util.UUID;

/**
 * Published by {@code AuthServiceImpl.logout()} for the currently authenticated principal.
 * Logout does not touch the database (no entity write for the Hibernate listener to see), so it
 * needs the same domain-event treatment as login.
 *
 * See docs/adr/0003-audit-log-persistence.md, section "Update — hybrid extension".
 */
public record LogoutEvent(UUID userId, String code) {
}
