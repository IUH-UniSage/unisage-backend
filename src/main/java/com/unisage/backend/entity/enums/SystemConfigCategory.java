package com.unisage.backend.entity.enums;

import lombok.Getter;

/**
 * Grouping used by the settings UI to organise {@link com.unisage.backend.entity.SystemConfig}
 * rows into tabs/sections (see UNISAGE-64/65).
 */
@Getter
public enum SystemConfigCategory {
    GENERAL("Chung"),
    SECURITY("Bảo mật"),
    INGEST("Nạp liệu"),
    CHAT("Trò chuyện"),
    AUDIT("Nhật ký hệ thống"),
    MAINTENANCE("Bảo trì hệ thống");

    private final String displayName;

    SystemConfigCategory(String displayName) {
        this.displayName = displayName;
    }
}
