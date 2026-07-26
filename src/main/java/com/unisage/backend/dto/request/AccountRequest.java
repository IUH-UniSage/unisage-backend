package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record AccountRequest(
    String email,
    String code,
    String password,
    Boolean isActive
) {}
