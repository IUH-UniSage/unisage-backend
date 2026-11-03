package com.unisage.backend.dto.response;

import java.util.UUID;

/** {@code POST /shares/view/{token}/continue}: the caller's copy (new, or the one made earlier). */
public record ContinueShareResponse(UUID conversationId) {}
