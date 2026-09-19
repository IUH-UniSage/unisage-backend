package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum TicketStatus {
    OPEN("Chờ xử lý"),
    PROCESSING("Đang xử lý"),
    RESOLVED("Đã giải quyết"),
    CLOSED("Đã đóng");

    private final String displayName;

    TicketStatus(String displayName) {
        this.displayName = displayName;
    }
}
