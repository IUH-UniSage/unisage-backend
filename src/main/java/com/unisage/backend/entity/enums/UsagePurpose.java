package com.unisage.backend.entity.enums;

/**
 * What kind of LLM/embedding call a {@code RequestUsageLog} row measures. Mirrors
 * {@link ChatModelPurpose} but is its own enum: a usage log outlives the {@code ChatModel} row it
 * was measured against (FK is {@code ON DELETE SET NULL}), so it needs a purpose value that does
 * not depend on any {@code ChatModel} still existing.
 */
public enum UsagePurpose {
    CHAT,
    EMBEDDING,
    EXTRACTION
}
