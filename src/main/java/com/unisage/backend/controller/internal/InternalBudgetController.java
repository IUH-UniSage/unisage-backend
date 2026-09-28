package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.internal.InternalBudgetSnapshotResponse;
import com.unisage.backend.service.budget.BudgetService;

import lombok.RequiredArgsConstructor;

/** Cost Tracking's internal namespace, same {@code /internal/**} security stack as
 * {@link InternalUsageLogController} - see that class's Javadoc. */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalBudgetController {

    private final BudgetService budgetService;

    @GetMapping("/budgets/snapshot")
    public InternalBudgetSnapshotResponse snapshot() {
        return budgetService.getSnapshot();
    }
}
