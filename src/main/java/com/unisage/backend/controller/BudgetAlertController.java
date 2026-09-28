package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.BudgetAlertLogResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.budget.BudgetAlertLogService;

import lombok.RequiredArgsConstructor;

/** Alert history + the in-app banner feed - plan.md "In-app alert"/"Giao diện quản lý ngân sách". */
@RestController
@RequestMapping("/budget-alerts")
@RequiredArgsConstructor
public class BudgetAlertController {

    private final BudgetAlertLogService budgetAlertLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<BudgetAlertLogResponse>>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(budgetAlertLogService.getAll(pageable)));
    }

    /** Polled by BudgetAlertBanner - undismissed IN_APP alerts only. */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<BudgetAlertLogResponse>>> getActive() {
        return ResponseEntity.ok(ApiResponse.success(budgetAlertLogService.getActive()));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismiss(@PathVariable UUID id) {
        budgetAlertLogService.dismiss(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
