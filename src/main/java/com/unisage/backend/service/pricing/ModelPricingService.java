package com.unisage.backend.service.pricing;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.ModelPriceRequest;
import com.unisage.backend.dto.response.ModelPriceResponse;

public interface ModelPricingService {

    List<ModelPriceResponse> getAll(String provider, String query);

    ModelPriceResponse create(ModelPriceRequest request);

    /** Pins the price as a MANUAL override the sync will no longer touch. */
    ModelPriceResponse update(UUID id, ModelPriceRequest request);

    /** Only MANUAL rows; the next sync recreates the LiteLLM price. */
    void reset(UUID id);
}
