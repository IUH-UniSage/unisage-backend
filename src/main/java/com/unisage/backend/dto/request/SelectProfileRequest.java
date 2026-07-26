package com.unisage.backend.dto.request;

import lombok.Builder;
import java.util.UUID;

@Builder
public record SelectProfileRequest(
    UUID userId
) {}
