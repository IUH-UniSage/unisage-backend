package com.unisage.backend.entity.enums;

/**
 * Whether a {@code RequestUsageLine}'s cost is a real priced amount. {@code PRICED} means LiteLLM
 * had a price for the model ({@code costUsd} is set); {@code UNPRICED} means it did not
 * ({@code costUsd} is {@code null}, {@code estimatedCostUsd} carries a conservative fallback
 * instead); {@code FREE} means the credential is {@code SELF_HOSTED} (never priced, never
 * estimated as non-zero).
 */
public enum UsageCostStatus {
    PRICED,
    UNPRICED,
    FREE
}
