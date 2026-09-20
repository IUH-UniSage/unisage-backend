package com.unisage.backend.entity.enums;

public enum AuditAction {
    CREATE, UPDATE, DELETE,

    // Non-DB-write security/compliance actions (UNISAGE-60 hybrid extension: domain events for
    // auth, AOP for sensitive-read access) — see docs/adr/0003-audit-log-persistence.md, section
    // "Update — hybrid extension".
    LOGIN, LOGIN_FAILED, LOGOUT, DOWNLOAD, VIEW
}
