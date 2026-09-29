package com.unisage.backend.service.usagelog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UsageLogDetailResponse;
import com.unisage.backend.dto.response.UsageLogListItemResponse;
import com.unisage.backend.dto.response.UsageLogSummaryResponse;
import com.unisage.backend.dto.response.internal.UsageLogIngestResponse;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;

public interface RequestUsageLogService {

    UsageLogIngestResponse ingest(UsageLogIngestRequest request);

    UsageLogSummaryResponse summary(LocalDateTime from, LocalDateTime to, String groupBy,
            UsagePurpose purposeFilter, String providerFilter);

    PageResponse<List<UsageLogListItemResponse>> search(
            UsagePurpose purpose, UsageRequestStatus status, LocalDateTime from, LocalDateTime to, Pageable pageable);

    UsageLogDetailResponse getDetail(UUID id);
}
