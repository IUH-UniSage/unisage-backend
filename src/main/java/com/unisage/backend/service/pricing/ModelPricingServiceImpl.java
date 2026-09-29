package com.unisage.backend.service.pricing;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.unisage.backend.dto.response.ModelPriceResponse;
import com.unisage.backend.entity.ModelPrice;
import com.unisage.backend.repository.ModelPriceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModelPricingServiceImpl implements ModelPricingService {

    private final ModelPriceRepository modelPriceRepository;

    /** Filtered in memory: the table only holds openai/google chat and embedding models. */
    @Override
    @Transactional(readOnly = true)
    public List<ModelPriceResponse> getAll(String provider, String query) {
        String providerFilter = StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : null;
        String queryFilter = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : null;
        return modelPriceRepository.findAllByOrderByProviderAscModelNameAsc().stream()
                .filter(price -> providerFilter == null || price.getProvider().equals(providerFilter))
                .filter(price -> queryFilter == null
                        || price.getModelName().toLowerCase(Locale.ROOT).contains(queryFilter))
                .map(ModelPricingServiceImpl::toResponse)
                .toList();
    }

    static ModelPriceResponse toResponse(ModelPrice price) {
        return ModelPriceResponse.builder()
                .id(price.getId())
                .provider(price.getProvider())
                .modelName(price.getModelName())
                .inputPerMillion(price.getInputPerMillion())
                .outputPerMillion(price.getOutputPerMillion())
                .cachedInputPerMillion(price.getCachedInputPerMillion())
                .source(price.getSource())
                .syncedAt(utc(price.getSyncedAt()))
                .updatedAt(utc(price.getUpdatedAt()))
                .updatedByEmail(price.getUpdatedBy() != null ? price.getUpdatedBy().getEmail() : null)
                .build();
    }

    static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
