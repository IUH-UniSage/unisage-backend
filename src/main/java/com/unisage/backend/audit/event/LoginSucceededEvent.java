package com.unisage.backend.audit.event;

import java.util.UUID;

/**
 * Published by {@code AuthServiceImpl.login(...)} right after the password check and account
 * status checks pass. This app does not authenticate via Spring Security's
 * {@code AuthenticationManager} (password is checked by hand with {@code passwordEncoder.matches}
 * against a manually-fetched {@code User}), so the built-in {@code AuthenticationSuccessEvent}
 * never fires for this flow — this is a genuine, hand-published domain event instead.
 *
 * See docs/adr/0003-audit-log-persistence.md, section "Update — hybrid extension".
 */
public record LoginSucceededEvent(UUID userId, String code) {
}
