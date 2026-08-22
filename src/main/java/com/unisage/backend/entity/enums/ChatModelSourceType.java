package com.unisage.backend.entity.enums;

import lombok.Getter;

@Getter
public enum ChatModelSourceType {
    CLOUD_API("Nhà cung cấp cloud"),
    SELF_HOSTED("Tự host");

    private final String displayName;

    ChatModelSourceType(String displayName) {
        this.displayName = displayName;
    }
}
