package com.unisage.backend.service.pricing;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.unisage.backend.dto.request.ModelPriceRequest;
import com.unisage.backend.dto.response.ModelPriceChangeResponse;
import com.unisage.backend.dto.response.ModelPriceResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.internal.InternalModelPricingSnapshotResponse;
import com.unisage.backend.entity.ModelPrice;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.ModelPriceChangeType;
import com.unisage.backend.entity.enums.ModelPriceSource;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ModelPriceRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.chatmodel.ChatModelServiceImpl;
import com.unisage.backend.service.modelregistryversion.ModelRegistryVersionService;
import com.unisage.backend.utils.SecurityUtil;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModelPricingServiceImpl implements ModelPricingService {

    private final ModelPriceRepository modelPriceRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final JdbcTemplate jdbcTemplate;
    private final ModelRegistryVersionService modelRegistryVersionService;
    private final Clock clock;

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

    @Override
    @Transactional
    public ModelPriceResponse create(ModelPriceRequest request) {
        String provider = request.provider() == null ? "" : request.provider().trim().toLowerCase(Locale.ROOT);
        String modelName = request.modelName() == null ? "" : request.modelName().trim();
        if (!ChatModelServiceImpl.SUPPORTED_LLM_PROVIDERS.contains(provider) || modelName.isEmpty()) {
            throw new AppException(ErrorCode.MODEL_PRICE_INVALID);
        }
        validatePrices(request);
        if (modelPriceRepository.existsByProviderAndModelName(provider, modelName)) {
            throw new AppException(ErrorCode.MODEL_PRICE_ALREADY_EXISTS);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User actor = currentUser();
        ModelPrice price = modelPriceRepository.save(ModelPrice.builder()
                .provider(provider)
                .modelName(modelName)
                .inputPerMillion(request.inputPerMillion())
                .outputPerMillion(request.outputPerMillion())
                .cachedInputPerMillion(request.cachedInputPerMillion())
                .source(ModelPriceSource.MANUAL)
                .createdAt(now)
                .updatedAt(now)
                .updatedBy(actor)
                .build());
        recordChange(price, null, ModelPriceChangeType.MANUAL_CREATE, actor, now);
        modelRegistryVersionService.bump();
        return toResponse(price);
    }

    @Override
    @Transactional
    public ModelPriceResponse update(UUID id, ModelPriceRequest request) {
        ModelPrice price = modelPriceRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MODEL_PRICE_NOT_FOUND));
        validatePrices(request);
        ModelPrice before = snapshot(price);
        boolean samePrices = equal(price.getInputPerMillion(), request.inputPerMillion())
                && equal(price.getOutputPerMillion(), request.outputPerMillion())
                && equal(price.getCachedInputPerMillion(), request.cachedInputPerMillion());
        if (samePrices && price.getSource() == ModelPriceSource.MANUAL) {
            return toResponse(price);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        User actor = currentUser();
        price.setInputPerMillion(request.inputPerMillion());
        price.setOutputPerMillion(request.outputPerMillion());
        price.setCachedInputPerMillion(request.cachedInputPerMillion());
        price.setSource(ModelPriceSource.MANUAL);
        price.setUpdatedAt(now);
        price.setUpdatedBy(actor);
        price = modelPriceRepository.save(price);
        recordChange(price, before, ModelPriceChangeType.MANUAL_UPDATE, actor, now);
        modelRegistryVersionService.bump();
        return toResponse(price);
    }

    @Override
    @Transactional
    public void reset(UUID id) {
        ModelPrice price = modelPriceRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.MODEL_PRICE_NOT_FOUND));
        if (price.getSource() != ModelPriceSource.MANUAL) {
            throw new AppException(ErrorCode.MODEL_PRICE_NOT_MANUAL);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ModelPrice removed = ModelPrice.builder()
                .provider(price.getProvider())
                .modelName(price.getModelName())
                .build();
        recordChange(removed, snapshot(price), ModelPriceChangeType.MANUAL_RESET, currentUser(), now);
        modelPriceRepository.delete(price);
        modelRegistryVersionService.bump();
    }

    @Override
    public PageResponse<List<ModelPriceChangeResponse>> getHistory(ModelPriceHistoryFilter filter, Pageable pageable) {
        // Only filters that were actually passed become predicates - Postgres can't type a bare
        // "? IS NULL" bind for timestamp columns.
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (StringUtils.hasText(filter.provider())) {
            where.append(" AND c.provider = ?");
            params.add(filter.provider().trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(filter.model())) {
            where.append(" AND c.model_name = ?");
            params.add(filter.model().trim());
        }
        if (StringUtils.hasText(filter.query())) {
            where.append(" AND lower(c.model_name) LIKE ?");
            params.add("%" + filter.query().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (filter.changeType() != null) {
            where.append(" AND c.change_type = ?");
            params.add(filter.changeType().name());
        }
        if (filter.from() != null) {
            where.append(" AND c.changed_at >= ?");
            params.add(Timestamp.valueOf(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND c.changed_at < ?");
            params.add(Timestamp.valueOf(filter.to()));
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_price_changes c" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageable.getPageSize());
        pageParams.add(pageable.getOffset());
        List<ModelPriceChangeResponse> rows = jdbcTemplate.query("""
                SELECT c.*, u.email AS changed_by_email
                FROM model_price_changes c
                LEFT JOIN users u ON u.id = c.changed_by
                """ + where + " ORDER BY c.changed_at DESC, c.id LIMIT ? OFFSET ?",
                (rs, rowNum) -> ModelPriceChangeResponse.builder()
                        .id(rs.getObject("id", UUID.class))
                        .provider(rs.getString("provider"))
                        .modelName(rs.getString("model_name"))
                        .changeType(ModelPriceChangeType.valueOf(rs.getString("change_type")))
                        .oldInputPerMillion(rs.getBigDecimal("old_input_per_million"))
                        .newInputPerMillion(rs.getBigDecimal("new_input_per_million"))
                        .oldOutputPerMillion(rs.getBigDecimal("old_output_per_million"))
                        .newOutputPerMillion(rs.getBigDecimal("new_output_per_million"))
                        .oldCachedInputPerMillion(rs.getBigDecimal("old_cached_input_per_million"))
                        .newCachedInputPerMillion(rs.getBigDecimal("new_cached_input_per_million"))
                        .changedByEmail(rs.getString("changed_by_email"))
                        .changedAt(utc(rs.getTimestamp("changed_at").toLocalDateTime()))
                        .build(),
                pageParams.toArray());
        return PageResponse.fromPageData(new PageImpl<>(rows, pageable, total == null ? 0 : total), rows);
    }

    @Override
    @Transactional(readOnly = true)
    public InternalModelPricingSnapshotResponse getSnapshot() {
        // Version first: a change committed between the two reads makes the agent poll again,
        // never keep a newer version with older prices.
        long version = modelRegistryVersionService.currentVersion();
        List<InternalModelPricingSnapshotResponse.PriceEntry> prices = modelPriceRepository.findAll().stream()
                .map(price -> InternalModelPricingSnapshotResponse.PriceEntry.builder()
                        .provider(price.getProvider())
                        .modelName(price.getModelName())
                        .inputPerMillion(price.getInputPerMillion())
                        .outputPerMillion(price.getOutputPerMillion())
                        .cachedInputPerMillion(price.getCachedInputPerMillion())
                        .build())
                .toList();
        return InternalModelPricingSnapshotResponse.builder().version(version).prices(prices).build();
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

    private static void validatePrices(ModelPriceRequest request) {
        if (!inRange(request.inputPerMillion()) || !inRange(request.outputPerMillion())
                || !inRange(request.cachedInputPerMillion()) || request.inputPerMillion() == null) {
            throw new AppException(ErrorCode.MODEL_PRICE_INVALID);
        }
    }

    private static boolean inRange(BigDecimal value) {
        return value == null
                || (value.signum() >= 0 && value.compareTo(LiteLlmPriceParser.MAX_PER_MILLION) <= 0);
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return Objects.equals(a, b) || (a != null && b != null && a.compareTo(b) == 0);
    }

    private static ModelPrice snapshot(ModelPrice price) {
        return ModelPrice.builder()
                .inputPerMillion(price.getInputPerMillion())
                .outputPerMillion(price.getOutputPerMillion())
                .cachedInputPerMillion(price.getCachedInputPerMillion())
                .build();
    }

    private User currentUser() {
        return userRepository.findById(securityUtil.getCurrentUserId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private void recordChange(ModelPrice after, ModelPrice before, ModelPriceChangeType type, User actor,
            LocalDateTime at) {
        jdbcTemplate.update(ModelPricingSyncServiceImpl.INSERT_CHANGE_SQL,
                UUID.randomUUID(), after.getProvider(), after.getModelName(), type.name(),
                before == null ? null : before.getInputPerMillion(), after.getInputPerMillion(),
                before == null ? null : before.getOutputPerMillion(), after.getOutputPerMillion(),
                before == null ? null : before.getCachedInputPerMillion(), after.getCachedInputPerMillion(),
                actor.getId(), Timestamp.valueOf(at));
    }
}
