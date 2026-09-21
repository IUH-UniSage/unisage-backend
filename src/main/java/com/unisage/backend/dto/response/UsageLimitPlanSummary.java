package com.unisage.backend.dto.response;

import java.util.UUID;

/** The usage limit plan a role points at, as shown in role responses. */
public record UsageLimitPlanSummary(UUID id, String name) {}
