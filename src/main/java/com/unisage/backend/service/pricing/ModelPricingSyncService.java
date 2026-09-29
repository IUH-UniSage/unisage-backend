package com.unisage.backend.service.pricing;

import com.unisage.backend.dto.response.ModelPricingSyncResponse;

public interface ModelPricingSyncService {

    /** Throws {@code AppException(MODEL_PRICING_SYNC_FAILED)} and changes nothing when the source
     * can't be fetched or parsed. */
    ModelPricingSyncResponse sync();
}
