package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.CreateConversationRequest;
import com.unisage.backend.dto.response.ConversationResponse;
import com.unisage.backend.entity.GuestSession;

public interface ConversationService {

    /**
     * {@code userId} is null for guest chat, in which case {@code guestSession} (already
     * resolved-or-created by the controller from the request cookie) is attached instead.
     */
    ConversationResponse create(CreateConversationRequest request, UUID userId, GuestSession guestSession);

    List<ConversationResponse> getByUser(UUID userId);

    /** Empty {@code guestSession} (no valid cookie) yields an empty list, never an error. */
    List<ConversationResponse> getByGuestSession(GuestSession guestSession);

    void softDelete(UUID id);

    /** Attaches a previously-guest (user == null) conversation to a now-logged-in user. */
    ConversationResponse claim(UUID conversationId, UUID userId);
}
