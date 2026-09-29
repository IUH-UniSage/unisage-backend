package com.unisage.backend.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ModelPriceResponse;
import com.unisage.backend.service.pricing.ModelPricingService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/model-pricing")
@RequiredArgsConstructor
public class ModelPricingController {

    private final ModelPricingService modelPricingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ModelPriceResponse>>> getAll(
            @RequestParam(required = false) String provider,
            @RequestParam(name = "q", required = false) String query) {
        return ResponseEntity.ok(ApiResponse.success(modelPricingService.getAll(provider, query)));
    }
}
