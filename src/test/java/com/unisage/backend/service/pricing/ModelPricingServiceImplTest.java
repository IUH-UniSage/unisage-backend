package com.unisage.backend.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.unisage.backend.dto.request.ModelPriceRequest;
import com.unisage.backend.entity.ModelPrice;
import com.unisage.backend.entity.enums.ModelPriceChangeType;
import com.unisage.backend.entity.enums.ModelPriceSource;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ModelPriceRepository;
import com.unisage.backend.security.UserPrincipal;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPricingServiceImplTest extends PostgresIntegrationTest {

    @Autowired
    private ModelPricingService service;

    @Autowired
    private ModelPriceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
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

    @Test
    void update_pinsPriceAsManualAndRecordsOldAndNew() {
        UUID actor = signInAsAdmin();
        UUID id = save("openai", "gpt-4o-mini");

        var response = service.update(id, request(null, null, "0.2"));

        assertThat(response.source()).isEqualTo(ModelPriceSource.MANUAL);
        assertThat(response.updatedByEmail()).isNotNull();
        var change = jdbcTemplate.queryForMap("SELECT * FROM model_price_changes");
        assertThat(change.get("change_type")).isEqualTo("MANUAL_UPDATE");
        assertThat((BigDecimal) change.get("old_input_per_million")).isEqualByComparingTo("0.15");
        assertThat((BigDecimal) change.get("new_input_per_million")).isEqualByComparingTo("0.2");
        assertThat(change.get("changed_by")).isEqualTo(actor);
    }

    @Test
    void create_rejectsUnsupportedProviderDuplicateAndOutOfRangePrice() {
        signInAsAdmin();
        save("openai", "gpt-4o-mini");

        assertError(() -> service.create(request("groq", "llama", "0.1")), ErrorCode.MODEL_PRICE_INVALID);
        assertError(() -> service.create(request("openai", "gpt-4o-mini", "0.1")),
                ErrorCode.MODEL_PRICE_ALREADY_EXISTS);
        assertError(() -> service.create(request("openai", "gpt-new", "1000.01")), ErrorCode.MODEL_PRICE_INVALID);
        assertError(() -> service.create(request("openai", "gpt-new", "-1")), ErrorCode.MODEL_PRICE_INVALID);

        var created = service.create(request("OpenAI", " gpt-new ", "0.5"));
        assertThat(created.provider()).isEqualTo("openai");
        assertThat(created.modelName()).isEqualTo("gpt-new");
        assertThat(created.source()).isEqualTo(ModelPriceSource.MANUAL);
    }

    @Test
    void reset_onlyDeletesManualPricesAndRecordsTheOldPrice() {
        signInAsAdmin();
        UUID synced = save("openai", "gpt-4o");
        UUID manual = service.create(request("openai", "gpt-new", "0.5")).id();

        assertError(() -> service.reset(synced), ErrorCode.MODEL_PRICE_NOT_MANUAL);
        service.reset(manual);

        assertThat(repository.existsById(manual)).isFalse();
        assertThat(jdbcTemplate.queryForList(
                "SELECT change_type FROM model_price_changes ORDER BY changed_at", String.class))
                .containsExactly("MANUAL_CREATE", "MANUAL_RESET");
    }

    @Test
    void getHistory_filtersAndPagesNewestFirst() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 0, 0);
        insertChange("openai", "gpt-4o", "SYNC_CREATE", base);
        insertChange("openai", "gpt-4o-mini", "SYNC_CREATE", base.plusDays(1));
        insertChange("google", "gemini-2.5-flash", "SYNC_CREATE", base.plusDays(2));
        insertChange("openai", "gpt-4o-mini", "SYNC_UPDATE", base.plusDays(3));

        var all = service.getHistory(filter(null, null, null, null, null, null), PageRequest.of(0, 3));
        assertThat(all.totalItems()).isEqualTo(4);
        assertThat(all.data()).extracting(c -> c.changedAt().toLocalDateTime())
                .containsExactly(base.plusDays(3), base.plusDays(2), base.plusDays(1));

        assertThat(service.getHistory(filter(null, "gpt-4o", null, null, null, null), PageRequest.of(0, 10))
                .totalItems()).isEqualTo(1);
        assertThat(service.getHistory(filter(null, null, "4O-", null, null, null), PageRequest.of(0, 10))
                .totalItems()).isEqualTo(2);
        assertThat(service.getHistory(filter("GOOGLE", null, null, null, null, null), PageRequest.of(0, 10))
                .totalItems()).isEqualTo(1);
        assertThat(service.getHistory(
                filter(null, null, null, ModelPriceChangeType.SYNC_UPDATE, null, null), PageRequest.of(0, 10))
                .totalItems()).isEqualTo(1);
        assertThat(service.getHistory(
                filter(null, null, null, null, base.plusDays(1), base.plusDays(3)), PageRequest.of(0, 10))
                .data()).extracting(c -> c.modelName()).containsExactly("gemini-2.5-flash", "gpt-4o-mini");
    }

    private static ModelPriceHistoryFilter filter(String provider, String model, String query,
            ModelPriceChangeType type, LocalDateTime from, LocalDateTime to) {
        return new ModelPriceHistoryFilter(provider, model, query, type, from, to);
    }

    private void insertChange(String provider, String model, String type, LocalDateTime at) {
        jdbcTemplate.update(ModelPricingSyncServiceImpl.INSERT_CHANGE_SQL, UUID.randomUUID(), provider, model, type,
                null, new BigDecimal("0.1"), null, null, null, null, null, java.sql.Timestamp.valueOf(at));
    }

    private UUID signInAsAdmin() {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = 'admin@unisage.com'", UUID.class);
        var principal = UserPrincipal.builder().userId(userId).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        return userId;
    }

    private static ModelPriceRequest request(String provider, String model, String input) {
        return new ModelPriceRequest(provider, model, new BigDecimal(input), null, null);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(code);
    }

    private UUID save(String provider, String model) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repository.save(ModelPrice.builder()
                .provider(provider)
                .modelName(model)
                .inputPerMillion(new BigDecimal("0.15"))
                .outputPerMillion(new BigDecimal("0.6"))
                .source(ModelPriceSource.LITELLM)
                .syncedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build()).getId();
    }
}
