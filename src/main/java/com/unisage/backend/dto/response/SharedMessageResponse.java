package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unisage.backend.entity.enums.MsgRole;

import lombok.Builder;

/**
 * A message as seen through a share link. Deliberately narrower than {@link MessageResponse}: no
 * conversation id, ticket, model or timing, and {@code metadata.calculation_feedback} (the
 * owner's own verdicts) is stripped. Retrieval/calculation traces are never part of it.
 */
@Builder
public record SharedMessageResponse(
    UUID id,
    MsgRole role,
    String content,
    List<Map<String, Object>> citations,
    Map<String, Object> metadata,
    LocalDateTime createdAt
) {}
