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
    EXTRACTION,
    /** One whole document ingestion job - every embedding/extraction call it made across every
     * chunk shares this ONE top-level request (see {@link com.unisage.backend.entity.Document}
     * on {@code RequestUsageLog}), not one request per call. */
    INGEST,
    /** One {@code POST /ingestion/chunking} call using the "semantic" strategy - every region's
     * embedding call it made shares this ONE top-level request, not one request per region. */
    SEMANTIC_CHUNKING
}
