package com.unisage.backend.service.conversation;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.ContinueShareResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.ShareListItemResponse;
import com.unisage.backend.dto.response.ShareResponse;
import com.unisage.backend.dto.response.SharedConversationResponse;

/**
 * Share links of a conversation (UNISAGE-107). The caller is always the signed-in user from the
 * JWT. Any link that can't be used - unknown token, revoked, conversation deleted, someone else's
 * link id - is {@code SHARE_NOT_FOUND}.
 */
public interface ConversationShareService {

    /** Owner only (else {@code CONVERSATION_NOT_FOUND}); snapshots every COMPLETED message so far. */
    ShareResponse create(UUID conversationId);

    /** The caller's live links, newest first. */
    PageResponse<List<ShareListItemResponse>> listMine(Pageable pageable);

    /** Idempotent for the caller's own link; anyone else's is {@code SHARE_NOT_FOUND}. */
    void revoke(UUID shareId);

    /** Revokes every live link of the caller; returns how many. */
    int revokeAll();

    SharedConversationResponse view(String token);

    /**
     * Copies the snapshot into a new conversation of the caller, or returns the caller's existing
     * (non-deleted) copy of this link.
     */
    ContinueShareResponse continueFromShare(String token);
}
