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

    /**
     * Owner only: another user's (or a guest's) conversation, or an already deleted one, is
     * {@code CONVERSATION_NOT_FOUND}. Revokes every share link of the conversation in the same
     * transaction (UNISAGE-107 R11).
     */
    void softDelete(UUID id, UUID callerId);

    /**
     * Keeps only the {@code keep} most recent conversations of each user/guest session and
     * soft-deletes the rest. Returns how many were deleted.
     */
    int trimToMaxPerOwner(int keep);

    /**
     * Attaches a previously-guest (user == null) conversation to a now-logged-in user. The caller
     * must present the same guest session that created it ({@code guestSession}, resolved by the
     * controller from the cookie); otherwise {@code CONVERSATION_NOT_FOUND}.
     */
    ConversationResponse claim(UUID conversationId, UUID userId, GuestSession guestSession);
}
