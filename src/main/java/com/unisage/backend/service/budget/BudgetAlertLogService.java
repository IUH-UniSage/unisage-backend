package com.unisage.backend.service.budget;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.BudgetAlertLogResponse;
import com.unisage.backend.dto.response.PageResponse;

public interface BudgetAlertLogService {

    PageResponse<List<BudgetAlertLogResponse>> getAll(Pageable pageable);

    List<BudgetAlertLogResponse> getActive();

    void dismiss(UUID id);
}
