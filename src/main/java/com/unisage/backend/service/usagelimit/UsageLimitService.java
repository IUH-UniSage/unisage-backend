package com.unisage.backend.service.usagelimit;

import java.util.UUID;

import com.unisage.backend.dto.response.UsageLimitResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;

public interface UsageLimitService {

    /**
     * Called before a USER message is stored. Exactly one of {@code user}/{@code guestSession} must
     * be non-null. Throws {@code USAGE_LIMIT_EXCEEDED} when any running window is already used up,
     * otherwise opens/refreshes the windows and counts the tokens of {@code question}.
     */
    void checkAndConsumeQuestion(User user, GuestSession guestSession, String question);

    /** Called when an ASSISTANT message completes: counts the tokens of {@code answer} in the running windows. */
    void consumeAnswer(User user, GuestSession guestSession, String answer);

    /**
     * Read-only view for the profile page / chat warning. Takes the user id (not the entity) so the
     * role's plan is loaded inside this service's transaction. With neither a user nor a guest session
     * (a guest that has not chatted yet) it reports the default plan with nothing used.
     */
    UsageLimitResponse getUsage(UUID userId, GuestSession guestSession);
}
