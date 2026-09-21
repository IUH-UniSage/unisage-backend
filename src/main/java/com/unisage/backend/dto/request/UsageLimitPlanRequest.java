package com.unisage.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * A null token limit means unlimited for that window. {@code isDefault} true makes this the one
 * default plan (the previous default is unset); null or false leaves it as is / not default.
 */
@Builder
public record UsageLimitPlanRequest(
    @NotBlank @Size(max = 255) String name,
    @Positive Long dailyTokenLimit,
    @Positive Long weeklyTokenLimit,
    Boolean isDefault
) {}
