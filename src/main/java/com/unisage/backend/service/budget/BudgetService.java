package com.unisage.backend.service.budget;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.BudgetRequest;
import com.unisage.backend.dto.response.BudgetResponse;
import com.unisage.backend.dto.response.internal.InternalBudgetSnapshotResponse;

public interface BudgetService {

    BudgetResponse create(BudgetRequest request);

    BudgetResponse update(UUID id, BudgetRequest request);

    BudgetResponse getById(UUID id);

    List<BudgetResponse> getAll();

    void delete(UUID id);

    /** {@code GET /internal/budgets/snapshot} - enabled budgets only, plus the current config version. */
    InternalBudgetSnapshotResponse getSnapshot();
}
