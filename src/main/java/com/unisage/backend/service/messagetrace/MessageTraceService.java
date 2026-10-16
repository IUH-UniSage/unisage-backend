package com.unisage.backend.service.messagetrace;

import com.unisage.backend.dto.request.internal.MessageTraceIngestRequest;
import com.unisage.backend.dto.response.internal.MessageTraceIngestResponse;

public interface MessageTraceService {

    /** Upserts the message's trace by {@code messageId}; safe to retry. */
    MessageTraceIngestResponse ingest(MessageTraceIngestRequest request);
}
