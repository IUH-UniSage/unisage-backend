package com.unisage.backend.entity.enums;

/**
 * Operational lifecycle of a {@code ChatModel} row — separate from {@code BaseEntity.isActive}
 * (soft-delete). Routing only ever uses a row with {@code isActive = true AND status = ACTIVE}.
 * See plan.md "State machine" for the full transition matrix; every transition goes through a
 * compare-and-set (`UPDATE ... WHERE status = :from`) so a lost race is a 409, never silent.
 */
public enum ChatModelStatus {
    /** Just created or credential changed and not yet verified against the provider. */
    PENDING,
    /** In the routing snapshot (only reachable with {@code isActive = true}). */
    ACTIVE,
    /** Verified at least once but not currently serving traffic (deactivated, or EMBEDDING that isn't the single active one). */
    INACTIVE,
    /** Circuit-broken by a PERMANENT health report; must be re-verified before it can activate again. */
    DISABLED
}
