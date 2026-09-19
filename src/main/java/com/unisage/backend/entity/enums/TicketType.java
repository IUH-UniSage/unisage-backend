package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum TicketType {
    AI_SYSTEM_ERROR("AI trả lời sai hoặc lỗi hệ thống"),
    AI_UNANSWERED("AI không trả lời được"),
    AI_SECURITY_BREACH("Nghi ngờ lộ thông tin bảo mật"),
    AI_INAPPROPRIATE("Nội dung không phù hợp"),
    OTHER("Khác");

    private final String displayName;

    TicketType(String displayName) {
        this.displayName = displayName;
    }
}
