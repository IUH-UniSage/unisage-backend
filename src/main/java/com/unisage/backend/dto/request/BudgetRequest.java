package com.unisage.backend.dto.request;

import java.math.BigDecimal;

import com.unisage.backend.entity.enums.BudgetAction;
import com.unisage.backend.entity.enums.BudgetPeriod;
import com.unisage.backend.entity.enums.BudgetScope;
import com.unisage.backend.entity.enums.UsagePurpose;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record BudgetRequest(
    @NotNull(message = "scope không được để trống")
    BudgetScope scope,

    /** Required iff scope = PROVIDER - checked in the service, not here (cross-field rule). */
    String scopeProvider,

    /** Required iff scope = PURPOSE - checked in the service, not here (cross-field rule). */
    UsagePurpose scopePurpose,

    @NotNull(message = "period không được để trống")
    BudgetPeriod period,

    @NotNull(message = "limitUsd không được để trống")
    @DecimalMin(value = "0", inclusive = false, message = "limitUsd phải > 0")
    BigDecimal limitUsd,

    @NotNull(message = "action không được để trống")
    BudgetAction action,

    /** Required iff action = THROTTLE - checked in the service, not here (cross-field rule). */
    Integer throttleMaxConcurrency,

    Boolean isEnabled
) {}
