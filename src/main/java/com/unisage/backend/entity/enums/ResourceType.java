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
    ACCESS_LEVEL("Cấp độ truy cập"),

    // AI / Chatbot
    CHAT_MODEL("Mô hình chat"),
    LLM_TRACE_LOG("Nhật ký LLM"),

    // Conversation
    CONVERSATION("Hội thoại"),
    MESSAGE("Tin nhắn"),

    // Usage
    USAGE_LIMIT_PLAN("Gói hạn mức"),

    // Cost Tracking
    USAGE_LOG("Nhật ký chi phí AI"),
    BUDGET("Ngân sách AI"),

    // Support
    TICKET("Yêu cầu hỗ trợ"),

    // Logs
    AUDIT_LOG("Nhật ký hệ thống"),

    // System
    SYSTEM("Hệ thống"),
    SYSTEM_CONFIG("Cấu hình hệ thống"),
    OTHER("Khác");

    private final String displayName;

    ResourceType(String displayName) {
        this.displayName = displayName;
    }
}
