package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record LoginRequest(
    String code,
    String password
) {}