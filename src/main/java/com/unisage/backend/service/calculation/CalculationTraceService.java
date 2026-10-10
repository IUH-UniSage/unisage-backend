package com.unisage.backend.service.calculation;

import com.unisage.backend.dto.request.internal.CalculationTraceIngestRequest;
import com.unisage.backend.dto.response.internal.CalculationTraceIngestResponse;

public interface CalculationTraceService {

    /** Upserts every item's trace by {@code (messageId, itemId)}; safe to retry. */
    CalculationTraceIngestResponse ingest(CalculationTraceIngestRequest request);
}
