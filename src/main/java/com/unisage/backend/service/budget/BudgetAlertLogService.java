package com.unisage.backend.service.budget;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.BudgetAlertLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;

public interface BudgetAlertLogService {

    PageResponse<List<BudgetAlertLogResponse>> getAll(Pageable pageable, AlertType alertType,
            AlertChannel channel, AlertStatus status);

    List<BudgetAlertLogResponse> getActive();

    void dismiss(UUID id);
}
