package com.unisage.backend.service.pricing;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.unisage.backend.dto.response.ModelPricingSyncResponse;
import com.unisage.backend.entity.enums.ModelPriceChangeType;
import com.unisage.backend.entity.enums.ModelPriceSource;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.integration.LiteLlmPriceClient;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.service.pricing.LiteLlmPriceParser.ParseResult;
import com.unisage.backend.service.pricing.LiteLlmPriceParser.ParsedPrice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes through {@link JdbcTemplate}, not JPA: the Hibernate audit listener would otherwise log
 * every synced row as an admin action. The price history of a sync lives in
 * {@code model_price_changes} instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelPricingSyncServiceImpl implements ModelPricingSyncService {

    private static final String INSERT_PRICE_SQL = """
            INSERT INTO model_prices (id, provider, model_name, input_per_million, output_per_million,
                                      cached_input_per_million, deprecation_date, source, synced_at,
                                      created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'LITELLM', ?, ?, ?)
            ON CONFLICT (provider, model_name) DO NOTHING
            """;

    // The source guard keeps an SA override safe even if it lands between our read and this write.
    private static final String UPDATE_PRICE_SQL = """
            UPDATE model_prices
            SET input_per_million = ?, output_per_million = ?, cached_input_per_million = ?,
                synced_at = ?, updated_at = ?, updated_by = NULL
            WHERE provider = ? AND model_name = ? AND source = 'LITELLM'
            """;

    private static final String TOUCH_SYNCED_AT_SQL = """
            UPDATE model_prices SET synced_at = ?
            WHERE provider = ? AND model_name = ? AND source = 'LITELLM'
            """;

    // Not a price change, so no history row; applies to SA-priced rows too.
    private static final String UPDATE_DEPRECATION_SQL = """
            UPDATE model_prices SET deprecation_date = ?
            WHERE provider = ? AND model_name = ?
            """;

    static final String INSERT_CHANGE_SQL = """
            INSERT INTO model_price_changes (id, provider, model_name, change_type,
                old_input_per_million, new_input_per_million, old_output_per_million, new_output_per_million,
                old_cached_input_per_million, new_cached_input_per_million, changed_by, changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final LiteLlmPriceClient priceClient;
    private final LiteLlmPriceParser priceParser;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ModelRegistryVersionService modelRegistryVersionService;
    private final Clock clock;

    @Override
    public ModelPricingSyncResponse sync() {
        ParseResult parsed;
        try {
            parsed = priceParser.parse(priceClient.fetch());
        } catch (LiteLlmPriceClient.PriceSourceException | IllegalArgumentException exc) {
            log.warn("model pricing sync: source unusable, keeping current prices ({})", exc.getMessage());
            throw new AppException(ErrorCode.MODEL_PRICING_SYNC_FAILED);
        }
        // Network I/O stays outside the transaction; only the writes hold a connection and the lock.
        return transactionTemplate.execute(status -> apply(parsed));
    }

    private ModelPricingSyncResponse apply(ParseResult parsed) {
        // Serializes the nightly job with a manual "sync now" on another instance.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext('model_pricing_sync'))", rs -> null);

        LocalDateTime now = LocalDateTime.now(clock);
        Timestamp nowTs = Timestamp.valueOf(now);
        Map<String, StoredPrice> stored = loadStored();
        List<ParsedPrice> inserts = new ArrayList<>();
        List<Object[]> changes = new ArrayList<>();
        List<Object[]> touched = new ArrayList<>();
        List<Object[]> deprecations = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skippedManual = 0;

        for (ParsedPrice price : parsed.prices()) {
            StoredPrice current = stored.get(key(price.provider(), price.modelName()));
            if (current != null && !Objects.equals(current.deprecationDate(), price.deprecationDate())) {
                deprecations.add(new Object[] {sqlDate(price.deprecationDate()), price.provider(), price.modelName()});
            }
            if (current == null) {
                inserts.add(price);
            } else if (current.source() == ModelPriceSource.MANUAL) {
                skippedManual++;
            } else if (current.samePrices(price)) {
                unchanged++;
                touched.add(new Object[] {nowTs, price.provider(), price.modelName()});
            } else {
                int rows = jdbcTemplate.update(UPDATE_PRICE_SQL, price.inputPerMillion(), price.outputPerMillion(),
                        price.cachedInputPerMillion(), nowTs, nowTs, price.provider(), price.modelName());
                if (rows == 1) {
                    updated++;
                    changes.add(changeRow(price, current, ModelPriceChangeType.SYNC_UPDATE, nowTs));
                }
            }
        }

        // Batched: the first sync inserts the whole price map (thousands of rows).
        int[] inserted = jdbcTemplate.batchUpdate(INSERT_PRICE_SQL, inserts.stream()
                .map(price -> new Object[] {UUID.randomUUID(), price.provider(), price.modelName(),
                    price.inputPerMillion(), price.outputPerMillion(), price.cachedInputPerMillion(),
                    sqlDate(price.deprecationDate()), nowTs, nowTs, nowTs})
                .toList());
        for (int i = 0; i < inserted.length; i++) {
            // 0 = an SA price for the same model was created after loadStored(); theirs stands.
            if (inserted[i] == 1 || inserted[i] == Statement.SUCCESS_NO_INFO) {
                created++;
                changes.add(changeRow(inserts.get(i), null, ModelPriceChangeType.SYNC_CREATE, nowTs));
            }
        }

        jdbcTemplate.batchUpdate(TOUCH_SYNCED_AT_SQL, touched);
        jdbcTemplate.batchUpdate(UPDATE_DEPRECATION_SQL, deprecations);
        jdbcTemplate.batchUpdate(INSERT_CHANGE_SQL, changes);
        if (created + updated > 0) {
            modelRegistryVersionService.bump();
        }
        log.info("model pricing sync: created={} updated={} unchanged={} skippedManual={} rejected={} "
                + "deprecationDatesChanged={}", created, updated, unchanged, skippedManual, parsed.rejected(),
                deprecations.size());

        return ModelPricingSyncResponse.builder()
                .created(created)
                .updated(updated)
                .unchanged(unchanged)
                .skippedManual(skippedManual)
                .rejected(parsed.rejected())
                .syncedAt(now.atOffset(ZoneOffset.UTC))
                .build();
    }

    private Map<String, StoredPrice> loadStored() {
        Map<String, StoredPrice> stored = new HashMap<>();
        jdbcTemplate.query("""
                SELECT provider, model_name, input_per_million, output_per_million, cached_input_per_million, source,
                       deprecation_date
                FROM model_prices
                """, rs -> {
            stored.put(key(rs.getString("provider"), rs.getString("model_name")), new StoredPrice(
                    rs.getBigDecimal("input_per_million"),
                    rs.getBigDecimal("output_per_million"),
                    rs.getBigDecimal("cached_input_per_million"),
                    ModelPriceSource.valueOf(rs.getString("source")),
                    rs.getObject("deprecation_date", LocalDate.class)));
        });
        return stored;
    }

    private static Object[] changeRow(ParsedPrice price, StoredPrice old, ModelPriceChangeType type, Timestamp at) {
        return new Object[] {
            UUID.randomUUID(), price.provider(), price.modelName(), type.name(),
            old == null ? null : old.input(), price.inputPerMillion(),
            old == null ? null : old.output(), price.outputPerMillion(),
            old == null ? null : old.cached(), price.cachedInputPerMillion(),
            null, at
        };
    }

    private static Date sqlDate(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static String key(String provider, String modelName) {
        return provider + "\u0000" + modelName;
    }

    private record StoredPrice(BigDecimal input, BigDecimal output, BigDecimal cached, ModelPriceSource source,
            LocalDate deprecationDate) {

        boolean samePrices(ParsedPrice price) {
            return equal(input, price.inputPerMillion())
                    && equal(output, price.outputPerMillion())
                    && equal(cached, price.cachedInputPerMillion());
        }

        private static boolean equal(BigDecimal a, BigDecimal b) {
            return Objects.equals(a, b) || (a != null && b != null && a.compareTo(b) == 0);
        }
    }
}
