package com.unisage.backend.entity.enums;

/**
 * What a {@code ChatModel} credential is used for. Routing only ever considers rows matching the
 * purpose it needs — see plan.md "Internal API contract" (snapshot grouped by purpose) and "State
 * machine". Every purpose accepts either {@link ChatModelSourceType} (plan.md "Open Questions") —
 * no purpose-specific source-type restriction is added here or anywhere else.
 */
public enum ChatModelPurpose {
    CHAT,
    EMBEDDING,
    EXTRACTION
}
