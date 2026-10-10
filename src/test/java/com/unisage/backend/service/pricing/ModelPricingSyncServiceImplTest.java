package com.unisage.backend.service.pricing;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPricingSyncServiceImplTest extends PostgresIntegrationTest {

    private static final HttpServer SERVER;
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicInteger STATUS = new AtomicInteger(200);

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exc) {
            throw new IllegalStateException(exc);
        }
        SERVER.createContext("/prices.json", exchange -> {
            byte[] bytes = BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(STATUS.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void priceSource(DynamicPropertyRegistry registry) {
        registry.add("app.model-pricing.source-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/prices.json");
    }

    private static final String FIXTURE = """
            {
              "sample_spec": {"litellm_provider": "openai", "mode": "chat", "input_cost_per_token": 0},
              "gpt-4o-mini": {"litellm_provider": "openai", "mode": "chat",
                              "input_cost_per_token": 1.5e-07, "output_cost_per_token": 6e-07,
                              "cache_read_input_token_cost": 7.5e-08, "deprecation_date": "2027-02-01"},
              "text-embedding-3-small": {"litellm_provider": "openai", "mode": "embedding",
                                         "input_cost_per_token": 2e-08, "output_cost_per_token": 0},
              "gemini/gemini-2.5-flash": {"litellm_provider": "gemini", "mode": "chat",
                                          "input_cost_per_token": 3e-07, "output_cost_per_token": 2.5e-06},
              "zai/glm-4.6": {"litellm_provider": "zai", "mode": "chat",
                              "input_cost_per_token": 6e-07, "output_cost_per_token": 2.2e-06,
                              "cache_read_input_token_cost": 1.1e-07},
              "fireworks_ai/glm-5p1": {"litellm_provider": "fireworks_ai", "mode": "chat",
                                       "input_cost_per_token": 1.4e-06},
              "gemini-2.5-flash": {"litellm_provider": "vertex_ai-language-models", "mode": "chat",
                                   "input_cost_per_token": 3e-07},
              "groq/llama-3.1-8b": {"litellm_provider": "groq", "mode": "chat", "input_cost_per_token": 5e-08},
              "deepseek-chat": {"litellm_provider": "deepseek", "mode": "chat", "input_cost_per_token": 1e-07},
              "deepseek/deepseek-chat": {"litellm_provider": "deepseek", "mode": "chat",
                                         "input_cost_per_token": 2.8e-07, "output_cost_per_token": 4.2e-07},
              "dall-e-2": {"litellm_provider": "openai", "mode": "image_generation", "input_cost_per_image": 0.02},
              "dall-e-3": {"litellm_provider": "openai", "mode": "image_generation", "input_cost_per_token": 1e-06},
              "ft:gpt-4o-mini": {"litellm_provider": "openai", "mode": "chat", "input_cost_per_token": 3e-07},
              "gpt-broken": {"litellm_provider": "openai", "mode": "chat", "input_cost_per_token": 0.5}
            }
            """;

    @Autowired
    private ModelPricingSyncService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void serveFixture() {
        BODY.set(FIXTURE);
        STATUS.set(200);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM model_price_changes");
        jdbcTemplate.update("DELETE FROM model_prices");
    }

    @Test
    void sync_storesEveryPerTokenPriceUnderItsProvider() {
        var result = service.sync();

        assertThat(result.created()).isEqualTo(9);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT provider || '/' || model_name FROM model_prices ORDER BY 1", String.class))
                .containsExactly("deepseek/deepseek-chat", "fireworks_ai/glm-5p1", "google/gemini-2.5-flash",
                        "groq/llama-3.1-8b", "openai/dall-e-3", "openai/gpt-4o-mini", "openai/text-embedding-3-small",
                        "vertex_ai-language-models/gemini-2.5-flash", "zai/glm-4.6");
        assertThat(price("openai", "gpt-4o-mini", "input_per_million")).isEqualByComparingTo("0.15");
        assertThat(price("openai", "gpt-4o-mini", "cached_input_per_million")).isEqualByComparingTo("0.075");
        assertThat(price("google", "gemini-2.5-flash", "output_per_million")).isEqualByComparingTo("2.5");
        assertThat(price("zai", "glm-4.6", "cached_input_per_million")).isEqualByComparingTo("0.11");
        // The prefixed key is the provider's own entry and wins over the bare duplicate.
        assertThat(price("deepseek", "deepseek-chat", "input_per_million")).isEqualByComparingTo("0.28");
        assertThat(changeCount("SYNC_CREATE")).isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deprecation_date::text FROM model_prices WHERE model_name = 'gpt-4o-mini'", String.class))
                .isEqualTo("2027-02-01");
    }

    @Test
    void sync_neverOverwritesManualPrice() {
        insertManual("openai", "gpt-4o-mini", "9.99");

        var result = service.sync();

        assertThat(result.skippedManual()).isEqualTo(1);
        assertThat(price("openai", "gpt-4o-mini", "input_per_million")).isEqualByComparingTo("9.99");
    }

    @Test
    void sync_recordsOldAndNewPriceWhenUpstreamChanges() {
        service.sync();
        BODY.set(FIXTURE.replace("\"input_cost_per_token\": 1.5e-07", "\"input_cost_per_token\": 2e-07"));

        var result = service.sync();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(price("openai", "gpt-4o-mini", "input_per_million")).isEqualByComparingTo("0.2");
        var change = jdbcTemplate.queryForMap(
                "SELECT old_input_per_million, new_input_per_million FROM model_price_changes WHERE change_type = 'SYNC_UPDATE'");
        assertThat((BigDecimal) change.get("old_input_per_million")).isEqualByComparingTo("0.15");
        assertThat((BigDecimal) change.get("new_input_per_million")).isEqualByComparingTo("0.2");
    }

    @Test
    void sync_twiceWithSameDataChangesNothingButSyncedAt() {
        service.sync();
        Timestamp updatedBefore = timestamp("updated_at");
        Timestamp syncedBefore = timestamp("synced_at");

        var result = service.sync();

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(9);
        assertThat(changeCount(null)).isEqualTo(9);
        assertThat(timestamp("updated_at")).isEqualTo(updatedBefore);
        assertThat(timestamp("synced_at")).isAfterOrEqualTo(syncedBefore);
    }

    @Test
    void sync_refreshesDeprecationDateWithoutHistoryEvenOnManualPrice() {
        insertManual("openai", "gpt-4o-mini", "9.99");
        service.sync();
        int changes = changeCount(null);

        BODY.set(FIXTURE.replace("2027-02-01", "2026-12-31"));
        service.sync();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deprecation_date::text FROM model_prices WHERE model_name = 'gpt-4o-mini'", String.class))
                .isEqualTo("2026-12-31");
        assertThat(price("openai", "gpt-4o-mini", "input_per_million")).isEqualByComparingTo("9.99");
        assertThat(changeCount(null)).isEqualTo(changes);
    }

    @Test
    void sync_failingSourceKeepsCurrentPrices() {
        service.sync();

        STATUS.set(500);
        assertSyncFails();
        STATUS.set(200);
        BODY.set("not json");
        assertSyncFails();
        BODY.set("{}");
        assertSyncFails();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM model_prices", Integer.class)).isEqualTo(9);
    }

    private void assertSyncFails() {
        assertThatThrownBy(() -> service.sync())
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.MODEL_PRICING_SYNC_FAILED);
    }

    private BigDecimal price(String provider, String model, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM model_prices WHERE provider = ? AND model_name = ?",
                BigDecimal.class, provider, model);
    }

    private Timestamp timestamp(String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM model_prices WHERE model_name = 'gpt-4o-mini'", Timestamp.class);
    }

    private int changeCount(String type) {
        return type == null
                ? jdbcTemplate.queryForObject("SELECT count(*) FROM model_price_changes", Integer.class)
                : jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM model_price_changes WHERE change_type = ?", Integer.class, type);
    }

    private void insertManual(String provider, String model, String input) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbcTemplate.update("""
                INSERT INTO model_prices (id, provider, model_name, input_per_million, source, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MANUAL', ?, ?)
                """, UUID.randomUUID(), provider, model, new BigDecimal(input), now, now);
    }
}
