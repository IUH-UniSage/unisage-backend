package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum TicketType {
    AI_SYSTEM_ERROR("AI trả lời sai hoặc lỗi hệ thống"),
    AI_UNANSWERED("AI không trả lời được"),
    AI_SECURITY_BREACH("Nghi ngờ lộ thông tin bảo mật"),
    AI_INAPPROPRIATE("Nội dung không phù hợp"),
    /** Either about a chat message or a general request without one. */
    OTHER("Khác"),
    /** Filed by the calculation feedback flow only (one per wrong item), never via POST /tickets. */
    AI_CALCULATION_WRONG("AI tính sai theo quy chế"),
    /** General request from the support page, never about a chat message. */
    GENERAL_QUESTION("Hỏi đáp chung"),
    /** General request from the support page, never about a chat message. */
    ACCOUNT_ISSUE("Tài khoản và đăng nhập");

    private final String displayName;

    TicketType(String displayName) {
        this.displayName = displayName;
    }
}
