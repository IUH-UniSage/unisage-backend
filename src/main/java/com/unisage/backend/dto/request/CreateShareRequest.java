package com.unisage.backend.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateShareRequest(@NotNull UUID conversationId) {}
