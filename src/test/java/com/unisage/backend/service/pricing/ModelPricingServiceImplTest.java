package com.unisage.backend.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.entity.ModelPrice;
import com.unisage.backend.entity.enums.ModelPriceSource;
import com.unisage.backend.repository.ModelPriceRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPricingServiceImplTest extends PostgresIntegrationTest {

    @Autowired
    private ModelPricingService service;

    @Autowired
    private ModelPriceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM model_price_changes");
        jdbcTemplate.update("DELETE FROM model_prices");
    }

    @Test
    void getAll_sortsByProviderThenModelAndFilters() {
        save("openai", "gpt-4o-mini");
        save("google", "gemini-2.5-flash");
        save("openai", "gpt-4o");

        assertThat(service.getAll(null, null))
                .extracting(p -> p.provider() + "/" + p.modelName())
                .containsExactly("google/gemini-2.5-flash", "openai/gpt-4o", "openai/gpt-4o-mini");
        assertThat(service.getAll("OpenAI", "MINI"))
                .extracting(p -> p.modelName())
                .containsExactly("gpt-4o-mini");
    }

    @Test
    void getAll_returnsTimestampsAsUtc() {
        save("openai", "gpt-4o-mini");

        var response = service.getAll(null, null).get(0);

        assertThat(response.updatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.inputPerMillion()).isEqualByComparingTo("0.15");
    }

    private void save(String provider, String model) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        repository.save(ModelPrice.builder()
                .provider(provider)
                .modelName(model)
                .inputPerMillion(new BigDecimal("0.15"))
                .outputPerMillion(new BigDecimal("0.6"))
                .source(ModelPriceSource.LITELLM)
                .syncedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}
