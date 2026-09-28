package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.BudgetRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.BudgetResponse;
import com.unisage.backend.service.budget.BudgetService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** SA-facing budget CRUD - plan.md "Internal API & bảo mật"/"Giao diện quản lý ngân sách". */
@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    @PostMapping
    public ResponseEntity<ApiResponse<BudgetResponse>> create(@Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(budgetService.create(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.getById(id)));
    }

    /** Budgets are a small, bounded config set (SYSTEM + one per provider/purpose) - same
     * no-pagination exception as AccessLevelController.getAllAccessLevels(). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BudgetResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(budgetService.getAll()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(ApiResponse.success(budgetService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        budgetService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
