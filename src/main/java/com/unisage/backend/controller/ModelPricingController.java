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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.ModelPriceRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ModelPriceResponse;
import com.unisage.backend.dto.response.ModelPricingSyncResponse;
import com.unisage.backend.service.pricing.ModelPricingService;
import com.unisage.backend.service.pricing.ModelPricingSyncService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/model-pricing")
@RequiredArgsConstructor
public class ModelPricingController {

    private final ModelPricingService modelPricingService;
    private final ModelPricingSyncService modelPricingSyncService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ModelPriceResponse>>> getAll(
            @RequestParam(required = false) String provider,
            @RequestParam(name = "q", required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(modelPricingService.getAll(provider, query)));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<ModelPricingSyncResponse>> sync() {
        return ResponseEntity.ok(ApiResponse.success(modelPricingSyncService.sync()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ModelPriceResponse>> create(@Valid @RequestBody ModelPriceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(modelPricingService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ModelPriceResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody ModelPriceRequest request) {
        return ResponseEntity.ok(ApiResponse.success(modelPricingService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> reset(@PathVariable UUID id) {
        modelPricingService.reset(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
