package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum ResourceType {
    // Auth & Access
    USER("Người dùng"),
    ROLE("Vai trò"),
    PERMISSION("Quyền hạn"),

    // Knowledge Base
    DEPARTMENT("Phòng ban"),
    USER_DEPARTMENT_ACCESS("Phân quyền truy cập phòng ban"),
    DOCUMENT("Tài liệu"),
    CATEGORY("Danh mục"),

    // AI / Chatbot
    CHAT_MODEL("Mô hình chat"),
    LLM_TRACE_LOG("Nhật ký LLM"),
    INGEST("Nạp liệu"),

    // Conversation
    CONVERSATION("Hội thoại"),
    MESSAGE("Tin nhắn"),

    // Logs
    AUDIT_LOG("Nhật ký hệ thống"),

    // System
    SYSTEM("Hệ thống"),
    OTHER("Khác");

    private final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }
}
