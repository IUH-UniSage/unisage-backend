package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.internal.InternalModelPricingSnapshotResponse;
import com.unisage.backend.service.pricing.ModelPricingService;

import lombok.RequiredArgsConstructor;

/** Same {@code /internal/**} security stack as {@link InternalBudgetController}. */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalModelPricingController {

    private final ModelPricingService modelPricingService;

    @GetMapping("/model-pricing/snapshot")
    public InternalModelPricingSnapshotResponse snapshot() {
        return modelPricingService.getSnapshot();
    }
}
