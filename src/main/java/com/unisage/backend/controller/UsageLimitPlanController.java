package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.UsageLimitPlanRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.UsageLimitPlanResponse;
import com.unisage.backend.service.usagelimit.UsageLimitPlanService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/usage-limit-plans")
@RequiredArgsConstructor
public class UsageLimitPlanController {

    private final UsageLimitPlanService usageLimitPlanService;

    @PostMapping
    public ResponseEntity<ApiResponse<UsageLimitPlanResponse>> createPlan(
            @Valid @RequestBody UsageLimitPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(usageLimitPlanService.createPlan(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UsageLimitPlanResponse>> getPlan(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(usageLimitPlanService.getPlanById(id)));
    }

    /** Small fixed set of plans, so a plain list instead of a page (same as access levels). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UsageLimitPlanResponse>>> getAllPlans() {
        return ResponseEntity.ok(ApiResponse.success(usageLimitPlanService.getAllPlans()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UsageLimitPlanResponse>> updatePlan(
            @PathVariable UUID id, @Valid @RequestBody UsageLimitPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(usageLimitPlanService.updatePlan(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePlan(@PathVariable UUID id) {
        usageLimitPlanService.deletePlan(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
