package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum ResourceType {
    // Auth & Access
    ACCOUNT("Tài khoản"),
    USER("Người dùng"),
    ROLE("Vai trò"),
    PERMISSION("Quyền hạn"),

    // Knowledge Base
    DOC_PACKAGE("Gói tài liệu"),
    DOCUMENT("Tài liệu"),
    DOCUMENT_CHUNK("Đoạn tài liệu"),
    CATEGORY("Danh mục"),

    // AI / Chatbot
    CHATBOT_CONFIG("Cấu hình chatbot"),
    CHATBOT_POOL("Pool chatbot"),
    EMBEDDED_MODEL("Mô hình nhúng"),
    LLM_TRACE_LOG("Nhật ký LLM"),
    INGEST("Nạp liệu"),

    // Conversation
    CONVERSATION("Hội thoại"),
    MESSAGE("Tin nhắn"),

    // Logs
    AUDIT_LOG("Nhật ký hệ thống"),
    DOCUMENT_PROCESS_LOG("Nhật ký xử lý tài liệu"),

    // System
    SYSTEM("Hệ thống"),
    OTHER("Khác");

    private final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }
}
