package com.unisage.backend.dto.request;

import lombok.Builder;

@Builder
public record ChangePasswordRequest(
    String password
) {}
