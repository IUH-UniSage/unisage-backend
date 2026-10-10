package com.unisage.backend.entity.enums;

import lombok.Getter;

/** Why a user marked a calculation result as wrong; required exactly when the verdict is WRONG. */
@Getter
public enum CalculationWrongReason {
    WRONG_FORMULA("Sai công thức"),
    WRONG_RESULT("Sai kết quả"),
    WRONG_SOURCE("Sai nguồn/quy chế"),
    MISSING_INFO("Thiếu thông tin"),
    OTHER("Khác");

    private final String displayName;

    CalculationWrongReason(String displayName) {
        this.displayName = displayName;
    }
}
