package com.unisage.backend.predefined;

/**
 * Single source of truth for every permission key in the system.
 *
 * Naming convention:  <RESOURCE>_<ACTION>
 *
 * Rules:
 *  - Each resource group has a <RESOURCE>_ALL wildcard (covers all HTTP methods on that resource path).
 *  - Constants are used as the `name` column value when seeding Permission rows in DataInitializer.
 *  - (Optionally) usable in @PreAuthorize annotations for method-level security.
 */
public final class PredefinedPermissions {
    private PredefinedPermissions() {}

    // ─── SUPER-ADMIN wildcard ──────────────────────────────────────────────
    public static final String SUPER_ADMIN_ALL = "SUPER_ADMIN_ALL";

    // ─── User ─────────────────────────────────────────────────────────────
    public static final String USER_ALL    = "USER_ALL";
    public static final String USER_READ   = "USER_READ";
    public static final String USER_CREATE = "USER_CREATE";
    public static final String USER_UPDATE = "USER_UPDATE";
    public static final String USER_DELETE = "USER_DELETE";

    // ─── Role ─────────────────────────────────────────────────────────────
    public static final String ROLE_ALL               = "ROLE_ALL";
    public static final String ROLE_READ              = "ROLE_READ";
    public static final String ROLE_CREATE            = "ROLE_CREATE";
    public static final String ROLE_UPDATE            = "ROLE_UPDATE";
    public static final String ROLE_DELETE            = "ROLE_DELETE";

    // ─── Permission ───────────────────────────────────────────────────────
    public static final String PERMISSION_ALL    = "PERMISSION_ALL";
    public static final String PERMISSION_READ   = "PERMISSION_READ";
    public static final String PERMISSION_CREATE = "PERMISSION_CREATE";
    public static final String PERMISSION_UPDATE = "PERMISSION_UPDATE";
    public static final String PERMISSION_DELETE = "PERMISSION_DELETE";

    // ─── Category ─────────────────────────────────────────────────────────
    public static final String CATEGORY_ALL    = "CATEGORY_ALL";
    public static final String CATEGORY_READ   = "CATEGORY_READ";
    public static final String CATEGORY_CREATE = "CATEGORY_CREATE";
    public static final String CATEGORY_UPDATE = "CATEGORY_UPDATE";
    public static final String CATEGORY_DELETE = "CATEGORY_DELETE";

    // ─── AccessLevel ──────────────────────────────────────────────────────
    public static final String ACCESS_LEVEL_ALL    = "ACCESS_LEVEL_ALL";
    public static final String ACCESS_LEVEL_READ   = "ACCESS_LEVEL_READ";
    public static final String ACCESS_LEVEL_CREATE = "ACCESS_LEVEL_CREATE";
    public static final String ACCESS_LEVEL_UPDATE = "ACCESS_LEVEL_UPDATE";
    public static final String ACCESS_LEVEL_DELETE = "ACCESS_LEVEL_DELETE";

    // ─── UsageLimitPlan ───────────────────────────────────────────────────
    public static final String USAGE_LIMIT_PLAN_ALL    = "USAGE_LIMIT_PLAN_ALL";
    public static final String USAGE_LIMIT_PLAN_READ   = "USAGE_LIMIT_PLAN_READ";
    public static final String USAGE_LIMIT_PLAN_CREATE = "USAGE_LIMIT_PLAN_CREATE";
    public static final String USAGE_LIMIT_PLAN_UPDATE = "USAGE_LIMIT_PLAN_UPDATE";
    public static final String USAGE_LIMIT_PLAN_DELETE = "USAGE_LIMIT_PLAN_DELETE";

    // ─── Ticket ───────────────────────────────────────────────────────────
    public static final String TICKET_ALL    = "TICKET_ALL";
    public static final String TICKET_READ   = "TICKET_READ";
    public static final String TICKET_CREATE = "TICKET_CREATE";
    public static final String TICKET_UPDATE = "TICKET_UPDATE";
    public static final String TICKET_DELETE = "TICKET_DELETE";

    // ─── Department (Knowledge-Base node) ──────────────────────────────────
    public static final String DEPARTMENT_ALL    = "DEPARTMENT_ALL";
    public static final String DEPARTMENT_READ   = "DEPARTMENT_READ";
    public static final String DEPARTMENT_CREATE = "DEPARTMENT_CREATE";
    public static final String DEPARTMENT_UPDATE = "DEPARTMENT_UPDATE";
    public static final String DEPARTMENT_DELETE = "DEPARTMENT_DELETE";

    // ─── Document ─────────────────────────────────────────────────────────
    // DOCUMENT with access levels
    public static final String DOCUMENT_ALL    = "DOCUMENT_ALL";
    public static final String DOCUMENT_READ   = "DOCUMENT_READ";
    public static final String DOCUMENT_CREATE = "DOCUMENT_CREATE";
    public static final String DOCUMENT_UPDATE = "DOCUMENT_UPDATE";
    public static final String DOCUMENT_DELETE = "DOCUMENT_DELETE";

    // ─── ChatModel ────────────────────────────────────────────────────────
    public static final String CHAT_MODEL_ALL    = "CHAT_MODEL_ALL";
    public static final String CHAT_MODEL_READ   = "CHAT_MODEL_READ";
    public static final String CHAT_MODEL_CREATE = "CHAT_MODEL_CREATE";
    public static final String CHAT_MODEL_UPDATE = "CHAT_MODEL_UPDATE";
    public static final String CHAT_MODEL_DELETE = "CHAT_MODEL_DELETE";
    /** PATCH /chat-models/{id}/status and /chat-models/{id}/priority — CHAT_MODEL_ALL already covers both for SUPER_ADMIN. */
    public static final String CHAT_MODEL_ACTIVATE = "CHAT_MODEL_ACTIVATE";
    public static final String CHAT_MODEL_VERIFY = "CHAT_MODEL_VERIFY";

    // ─── Conversation ─────────────────────────────────────────────────────
    public static final String CONVERSATION_ALL    = "CONVERSATION_ALL";
    public static final String CONVERSATION_READ   = "CONVERSATION_READ";
    public static final String CONVERSATION_CREATE = "CONVERSATION_CREATE";
    public static final String CONVERSATION_DELETE = "CONVERSATION_DELETE";

    // ─── Message ──────────────────────────────────────────────────────────
    public static final String MESSAGE_ALL    = "MESSAGE_ALL";
    public static final String MESSAGE_READ   = "MESSAGE_READ";
    public static final String MESSAGE_SEND   = "MESSAGE_SEND";
    public static final String MESSAGE_UPDATE = "MESSAGE_UPDATE";

    // ─── AuditLog ─────────────────────────────────────────────────────────
    public static final String AUDIT_LOG_ALL  = "AUDIT_LOG_ALL";
    public static final String AUDIT_LOG_READ = "AUDIT_LOG_READ";

    // ─── LlmTraceLog ──────────────────────────────────────────────────────
    public static final String LLM_TRACE_LOG_ALL  = "LLM_TRACE_LOG_ALL";
    public static final String LLM_TRACE_LOG_READ = "LLM_TRACE_LOG_READ";

    // ─── SystemConfig ─────────────────────────────────────────────────────
    public static final String SYSTEM_CONFIG_READ   = "SYSTEM_CONFIG_READ";
    public static final String SYSTEM_CONFIG_UPDATE = "SYSTEM_CONFIG_UPDATE";

    // ─── SystemHealth ─────────────────────────────────────────────────────
    // Read-only: nothing here is admin-editable, so no corresponding _UPDATE/_ALL.
    public static final String SYSTEM_HEALTH_READ = "SYSTEM_HEALTH_READ";
}
