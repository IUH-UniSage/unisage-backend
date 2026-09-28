package com.unisage.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.BudgetAlertSettingRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.BudgetAlertSettingResponse;
import com.unisage.backend.service.budget.BudgetAlertSettingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Only GET/PUT - plan.md "BudgetAlertSetting": a second row can never be created. */
@RestController
@RequestMapping("/budget-alert-settings")
@RequiredArgsConstructor
public class BudgetAlertSettingController {

    private final BudgetAlertSettingService service;

    @GetMapping
    public ResponseEntity<ApiResponse<BudgetAlertSettingResponse>> get() {
        return ResponseEntity.ok(ApiResponse.success(service.get()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<BudgetAlertSettingResponse>> update(
            @Valid @RequestBody BudgetAlertSettingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(request)));
    }
}
