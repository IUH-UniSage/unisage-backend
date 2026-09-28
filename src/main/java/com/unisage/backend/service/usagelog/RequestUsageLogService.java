package com.unisage.backend.service.usagelog;

import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.dto.response.internal.UsageLogIngestResponse;

public interface RequestUsageLogService {

    UsageLogIngestResponse ingest(UsageLogIngestRequest request);
}
