package com.unisage.backend.dto.response;

import lombok.Builder;

@Builder
public record UsageLimitResponse(
        UsageWindowResponse daily,
        UsageWindowResponse weekly
) {}
