package com.unisage.backend.service.budget;

import com.unisage.backend.dto.request.BudgetAlertSettingRequest;
import com.unisage.backend.dto.response.BudgetAlertSettingResponse;

public interface BudgetAlertSettingService {

    BudgetAlertSettingResponse get();

    BudgetAlertSettingResponse update(BudgetAlertSettingRequest request);
}
