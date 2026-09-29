package com.unisage.backend.service.pricing;

import java.util.List;

import com.unisage.backend.dto.response.ModelPriceResponse;

public interface ModelPricingService {

    List<ModelPriceResponse> getAll(String provider, String query);
}
