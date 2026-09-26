package com.unisage.backend.entity.enums;

/**
 * Classifies a provider error reported by {@code unisage-agent} via
 * {@code POST /internal/model-registry/credentials/{id}/health} — plan.md "Internal API contract"
 * endpoint #3.
 */
public enum CredentialHealthErrorType {
    /** Rate limit, timeout, transient 5xx — retry with another credential, never disables the row. */
    TRANSIENT,
    /** Revoked/invalid key, out of credit — circuit-breaks an {@code ACTIVE} row to {@code DISABLED}. */
    PERMANENT
}
