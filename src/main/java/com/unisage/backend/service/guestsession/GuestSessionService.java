package com.unisage.backend.service.guestsession;

import java.util.Optional;

import com.unisage.backend.entity.GuestSession;

public interface GuestSessionService {

    /**
     * Reuses the session identified by {@code cookieTokenOrNull} if it is still valid (refreshing
     * its sliding expiry), or mints a brand-new one otherwise. Never returns empty. Only called
     * from conversation creation — the sole place a new guest session may be minted.
     */
    GuestSessionResolution resolveOrCreate(String cookieTokenOrNull);

    /**
     * Looks up the session identified by {@code cookieTokenOrNull}, refreshing its sliding expiry
     * if found. Never creates a new session — a missing/invalid/expired token yields empty,
     * meaning "no valid guest session", not "mint one".
     */
    Optional<GuestSessionResolution> resolveAndTouch(String cookieTokenOrNull);

    /**
     * Looks up the session identified by {@code cookieTokenOrNull} without creating one or
     * refreshing its expiry (read-only). A missing/invalid/expired token yields empty.
     */
    Optional<GuestSession> resolveReadOnly(String cookieTokenOrNull);

    record GuestSessionResolution(GuestSession session, String rawToken) {
    }
}
