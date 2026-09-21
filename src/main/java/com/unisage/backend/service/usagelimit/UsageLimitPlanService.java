package com.unisage.backend.service.usagelimit;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.UsageLimitPlanRequest;
import com.unisage.backend.dto.response.UsageLimitPlanResponse;

public interface UsageLimitPlanService {

    UsageLimitPlanResponse createPlan(UsageLimitPlanRequest request);

    UsageLimitPlanResponse updatePlan(UUID id, UsageLimitPlanRequest request);

    UsageLimitPlanResponse getPlanById(UUID id);

    List<UsageLimitPlanResponse> getAllPlans();

    void deletePlan(UUID id);
}
