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

    // ─── Account ──────────────────────────────────────────────────────────
    public static final String ACCOUNT_ALL          = "ACCOUNT_ALL";
    public static final String ACCOUNT_READ         = "ACCOUNT_READ";
    public static final String ACCOUNT_CREATE       = "ACCOUNT_CREATE";
    public static final String ACCOUNT_UPDATE       = "ACCOUNT_UPDATE";
    public static final String ACCOUNT_DELETE       = "ACCOUNT_DELETE";
    public static final String ACCOUNT_TOGGLE_ACTIVE = "ACCOUNT_TOGGLE_ACTIVE";

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

    // ─── DocPackage (Knowledge-Base node) ─────────────────────────────────
    public static final String DOC_PACKAGE_ALL    = "DOC_PACKAGE_ALL";
    public static final String DOC_PACKAGE_READ   = "DOC_PACKAGE_READ";
    public static final String DOC_PACKAGE_CREATE = "DOC_PACKAGE_CREATE";
    public static final String DOC_PACKAGE_UPDATE = "DOC_PACKAGE_UPDATE";
    public static final String DOC_PACKAGE_DELETE = "DOC_PACKAGE_DELETE";

    // ─── Document ─────────────────────────────────────────────────────────
    // DOCUMENT with access levels
    public static final String DOCUMENT_ALL    = "DOCUMENT_ALL";
    public static final String DOCUMENT_READ   = "DOCUMENT_READ";
    public static final String DOCUMENT_CREATE = "DOCUMENT_CREATE";
    public static final String DOCUMENT_UPDATE = "DOCUMENT_UPDATE";
    public static final String DOCUMENT_DELETE = "DOCUMENT_DELETE";


    // ─── Document Chunk ───────────────────────────────────────────────────
    public static final String DOCUMENT_CHUNK_ALL    = "DOCUMENT_CHUNK_ALL";
    public static final String DOCUMENT_CHUNK_READ   = "DOCUMENT_CHUNK_READ";
    public static final String DOCUMENT_CHUNK_DELETE = "DOCUMENT_CHUNK_DELETE";

    // ─── EmbeddedModel ────────────────────────────────────────────────────
    public static final String EMBEDDED_MODEL_ALL    = "EMBEDDED_MODEL_ALL";
    public static final String EMBEDDED_MODEL_READ   = "EMBEDDED_MODEL_READ";
    public static final String EMBEDDED_MODEL_CREATE = "EMBEDDED_MODEL_CREATE";
    public static final String EMBEDDED_MODEL_UPDATE = "EMBEDDED_MODEL_UPDATE";
    public static final String EMBEDDED_MODEL_DELETE = "EMBEDDED_MODEL_DELETE";

    // ─── ChatbotConfig ────────────────────────────────────────────────────
    public static final String CHATBOT_CONFIG_ALL    = "CHATBOT_CONFIG_ALL";
    public static final String CHATBOT_CONFIG_READ   = "CHATBOT_CONFIG_READ";
    public static final String CHATBOT_CONFIG_CREATE = "CHATBOT_CONFIG_CREATE";
    public static final String CHATBOT_CONFIG_UPDATE = "CHATBOT_CONFIG_UPDATE";
    public static final String CHATBOT_CONFIG_DELETE = "CHATBOT_CONFIG_DELETE";

    // ─── ChatbotPool ──────────────────────────────────────────────────────
    public static final String CHATBOT_POOL_ALL    = "CHATBOT_POOL_ALL";
    public static final String CHATBOT_POOL_READ   = "CHATBOT_POOL_READ";
    public static final String CHATBOT_POOL_CREATE = "CHATBOT_POOL_CREATE";
    public static final String CHATBOT_POOL_UPDATE = "CHATBOT_POOL_UPDATE";
    public static final String CHATBOT_POOL_DELETE = "CHATBOT_POOL_DELETE";

    // ─── Conversation ─────────────────────────────────────────────────────
    public static final String CONVERSATION_ALL    = "CONVERSATION_ALL";
    public static final String CONVERSATION_READ   = "CONVERSATION_READ";
    public static final String CONVERSATION_CREATE = "CONVERSATION_CREATE";
    public static final String CONVERSATION_DELETE = "CONVERSATION_DELETE";

    // ─── Message ──────────────────────────────────────────────────────────
    public static final String MESSAGE_ALL  = "MESSAGE_ALL";
    public static final String MESSAGE_READ = "MESSAGE_READ";
    public static final String MESSAGE_SEND = "MESSAGE_SEND";

    // ─── AuditLog ─────────────────────────────────────────────────────────
    public static final String AUDIT_LOG_ALL  = "AUDIT_LOG_ALL";
    public static final String AUDIT_LOG_READ = "AUDIT_LOG_READ";

    // ─── DocumentProcessLog ───────────────────────────────────────────────
    public static final String DOCUMENT_PROCESS_LOG_ALL  = "DOCUMENT_PROCESS_LOG_ALL";
    public static final String DOCUMENT_PROCESS_LOG_READ = "DOCUMENT_PROCESS_LOG_READ";

    // ─── LlmTraceLog ──────────────────────────────────────────────────────
    public static final String LLM_TRACE_LOG_ALL  = "LLM_TRACE_LOG_ALL";
    public static final String LLM_TRACE_LOG_READ = "LLM_TRACE_LOG_READ";

    // ─── Ingest ──────────────────────────────────────────────────────────
    public static final String INGEST_ALL = "INGEST_ALL";
}
