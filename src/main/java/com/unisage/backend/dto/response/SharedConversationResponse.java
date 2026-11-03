package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;

/** {@code GET /shares/view/{token}}: the snapshot behind a share link. */
@Builder
public record SharedConversationResponse(
    String title,
    /** When the link was made. */
    LocalDateTime createdAt,
    @JsonProperty("isOwner") boolean isOwner,
    /** The original conversation - only for the link's owner ("Mở hội thoại gốc"); omitted otherwise. */
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID conversationId,
    List<SharedMessageResponse> messages
) {}
