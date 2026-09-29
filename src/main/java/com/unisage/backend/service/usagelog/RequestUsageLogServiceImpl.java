package com.unisage.backend.service.usagelog;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.unisage.backend.dto.request.internal.UsageLineIngestRequest;
import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UsageLineResponse;
import com.unisage.backend.dto.response.UsageLogDetailResponse;
import com.unisage.backend.dto.response.UsageLogListItemResponse;
import com.unisage.backend.dto.response.UsageLogSummaryResponse;
import com.unisage.backend.dto.response.internal.UsageLogIngestResponse;
import com.unisage.backend.entity.RequestUsageLine;
import com.unisage.backend.entity.RequestUsageLog;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.RequestUsageLineRepository;
import com.unisage.backend.repository.RequestUsageLogRepository;
import com.unisage.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Backs {@code POST /internal/usage-logs} - plan.md "Internal API & bảo mật". Idempotency is a
 * native {@code INSERT ... ON CONFLICT (request_id) DO NOTHING}, not a JPA save wrapped in a
 * try/catch: a unique-constraint violation aborts the whole Postgres transaction, so a caught
 * {@code DataIntegrityViolationException} would leave no way to run the follow-up lookup in the
 * SAME transaction. {@code ON CONFLICT DO NOTHING} never raises - it just reports 0 rows affected.
 */
@Service
@RequiredArgsConstructor
public class RequestUsageLogServiceImpl implements RequestUsageLogService {

    private static final String INSERT_LOG_SQL = """
            INSERT INTO request_usage_logs
                (id, request_id, purpose, conversation_id, user_message_id, assistant_message_id, user_id,
                 guest_ip, status, total_input_tokens, total_output_tokens, total_cached_tokens,
                 total_cost_usd, estimated_unpriced_cost_usd, unpriced_line_count, line_count,
                 latency_ms, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (request_id) DO NOTHING
            """;

    private static final Sort DEFAULT_LIST_SORT = Sort.by(Sort.Direction.DESC, "startedAt");

    private final JdbcTemplate jdbcTemplate;
    private final RequestUsageLineRepository requestUsageLineRepository;
    private final RequestUsageLogRepository requestUsageLogRepository;
    private final UserRepository userRepository;

    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private String appTimezone;

    @Override
    @Transactional
    public UsageLogIngestResponse ingest(UsageLogIngestRequest request) {
        validate(request);

        UUID newId = UUID.randomUUID();
        Aggregates aggregates = Aggregates.of(request.lines());

        int insertedRows = jdbcTemplate.update(INSERT_LOG_SQL,
                newId,
                request.requestId(),
                request.purpose().name(),
                request.conversationId(),
                request.userMessageId(),
                request.assistantMessageId(),
                request.userId(),
                request.guestIp(),
                request.status().name(),
                aggregates.totalInputTokens(),
                aggregates.totalOutputTokens(),
                aggregates.totalCachedTokens(),
                aggregates.totalCostUsd(),
                aggregates.estimatedUnpricedCostUsd(),
                aggregates.unpricedLineCount(),
                request.lines().size(),
                latencyMs(request.startedAt(), request.finishedAt()),
                toUtc(request.startedAt()),
                toUtc(request.finishedAt()));

        if (insertedRows == 0) {
            UUID existingId = jdbcTemplate.queryForObject(
                    "SELECT id FROM request_usage_logs WHERE request_id = ?", UUID.class, request.requestId());
            return new UsageLogIngestResponse(existingId, true);
        }

        RequestUsageLog logReference = RequestUsageLog.builder().id(newId).build();
        List<RequestUsageLine> lines = request.lines().stream()
                .map(line -> toEntity(line, logReference))
                .toList();
        requestUsageLineRepository.saveAll(lines);

        return new UsageLogIngestResponse(newId, false);
    }

    @Override
    public UsageLogSummaryResponse summary(LocalDateTime from, LocalDateTime to, String groupBy,
            UsagePurpose purposeFilter, String providerFilter) {
        // "purpose"/"user"/"day" query request_usage_logs alone - filtering by provider there
        // means "this request touched that provider on at least one line attempt", checked via
        // EXISTS rather than a JOIN so a request with 2 lines on the same provider still counts
        // once, keeping COUNT(*)/SUM(...) semantics identical to the unfiltered query.
        String logProviderFilter = providerFilter == null ? "" : """
                AND EXISTS (
                    SELECT 1 FROM request_usage_lines pf
                    WHERE pf.usage_log_id = request_usage_logs.id AND lower(pf.provider) = lower(?)
                )""";
        String logPurposeFilter = purposeFilter == null ? "" : "AND purpose = ?";
        // "provider"/"model" already query request_usage_lines directly - purpose lives on the
        // joined parent, provider is the line's own column.
        String lineProviderFilter = providerFilter == null ? "" : "AND lower(l.provider) = lower(?)";
        String linePurposeFilter = purposeFilter == null ? "" : "AND g.purpose = ?";

        List<Object> logParams = new ArrayList<>(List.of(from, to));
        if (purposeFilter != null) logParams.add(purposeFilter.name());
        if (providerFilter != null) logParams.add(providerFilter);

        List<Object> lineParams = new ArrayList<>(List.of(from, to));
        if (purposeFilter != null) lineParams.add(purposeFilter.name());
        if (providerFilter != null) lineParams.add(providerFilter);

        List<UsageLogSummaryResponse.Bucket> buckets = switch (groupBy) {
            case "purpose" -> jdbcTemplate.query("""
                    SELECT purpose AS bucket_key, SUM(total_cost_usd) AS priced, SUM(estimated_unpriced_cost_usd) AS unpriced,
                           COUNT(*) AS request_count, SUM(total_input_tokens) AS input_tokens, SUM(total_output_tokens) AS output_tokens
                    FROM request_usage_logs
                    WHERE started_at >= ? AND started_at < ? %s %s
                    GROUP BY purpose ORDER BY purpose
                    """.formatted(logPurposeFilter, logProviderFilter), this::mapSummaryRow, logParams.toArray());
            case "user" -> jdbcTemplate.query("""
                    SELECT COALESCE(u.email, user_id::text, guest_ip, 'unknown') AS bucket_key,
                           SUM(total_cost_usd) AS priced, SUM(estimated_unpriced_cost_usd) AS unpriced,
                           COUNT(*) AS request_count, SUM(total_input_tokens) AS input_tokens, SUM(total_output_tokens) AS output_tokens
                    FROM request_usage_logs
                    LEFT JOIN users u ON u.id = request_usage_logs.user_id
                    WHERE started_at >= ? AND started_at < ? %s %s
                    GROUP BY bucket_key ORDER BY priced DESC NULLS LAST
                    """.formatted(logPurposeFilter, logProviderFilter), this::mapSummaryRow, logParams.toArray());
            case "day" -> {
                List<Object> dayParams = new ArrayList<>();
                dayParams.add(appTimezone);
                dayParams.addAll(logParams);
                yield jdbcTemplate.query("""
                        SELECT to_char((started_at AT TIME ZONE 'UTC') AT TIME ZONE ?, 'YYYY-MM-DD') AS bucket_key,
                               SUM(total_cost_usd) AS priced, SUM(estimated_unpriced_cost_usd) AS unpriced,
                               COUNT(*) AS request_count, SUM(total_input_tokens) AS input_tokens, SUM(total_output_tokens) AS output_tokens
                        FROM request_usage_logs
                        WHERE started_at >= ? AND started_at < ? %s %s
                        GROUP BY bucket_key ORDER BY bucket_key
                        """.formatted(logPurposeFilter, logProviderFilter), this::mapSummaryRow, dayParams.toArray());
            }
            case "provider" -> jdbcTemplate.query("""
                    SELECT l.provider AS bucket_key,
                           SUM(CASE WHEN l.cost_status = 'PRICED' THEN l.cost_usd ELSE 0 END) AS priced,
                           SUM(CASE WHEN l.cost_status = 'UNPRICED' THEN l.estimated_cost_usd ELSE 0 END) AS unpriced,
                           COUNT(DISTINCT l.usage_log_id) AS request_count,
                           SUM(l.input_tokens) AS input_tokens, SUM(l.output_tokens) AS output_tokens
                    FROM request_usage_lines l
                    JOIN request_usage_logs g ON g.id = l.usage_log_id
                    WHERE g.started_at >= ? AND g.started_at < ? %s %s
                    GROUP BY l.provider ORDER BY priced DESC
                    """.formatted(linePurposeFilter, lineProviderFilter), this::mapSummaryRow, lineParams.toArray());
            case "model" -> jdbcTemplate.query("""
                    SELECT l.model_name AS bucket_key,
                           SUM(CASE WHEN l.cost_status = 'PRICED' THEN l.cost_usd ELSE 0 END) AS priced,
                           SUM(CASE WHEN l.cost_status = 'UNPRICED' THEN l.estimated_cost_usd ELSE 0 END) AS unpriced,
                           COUNT(DISTINCT l.usage_log_id) AS request_count,
                           SUM(l.input_tokens) AS input_tokens, SUM(l.output_tokens) AS output_tokens
                    FROM request_usage_lines l
                    JOIN request_usage_logs g ON g.id = l.usage_log_id
                    WHERE g.started_at >= ? AND g.started_at < ? %s %s
                    GROUP BY l.model_name ORDER BY priced DESC
                    """.formatted(linePurposeFilter, lineProviderFilter), this::mapSummaryRow, lineParams.toArray());
            default -> throw new AppException(ErrorCode.USAGE_LOG_INVALID_PAYLOAD,
                    Map.of("groupBy", "Phải là một trong: purpose, provider, model, user, day"));
        };

        return UsageLogSummaryResponse.builder().groupBy(groupBy).buckets(buckets).build();
    }

    private UsageLogSummaryResponse.Bucket mapSummaryRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return UsageLogSummaryResponse.Bucket.builder()
                .key(rs.getString("bucket_key"))
                .pricedCostUsd(rs.getBigDecimal("priced"))
                .estimatedUnpricedCostUsd(rs.getBigDecimal("unpriced"))
                .requestCount(rs.getLong("request_count"))
                .totalInputTokens(rs.getLong("input_tokens"))
                .totalOutputTokens(rs.getLong("output_tokens"))
                .build();
    }

    @Override
    public PageResponse<List<UsageLogListItemResponse>> search(UsageLogSearchFilter filter, Pageable pageable) {
        Pageable sorted = pageable.isPaged() && pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_LIST_SORT)
                : pageable;
        Page<RequestUsageLog> page = requestUsageLogRepository.findAll(buildSpec(filter), sorted);

        List<UUID> ids = page.getContent().stream().map(RequestUsageLog::getId).toList();
        Set<UUID> withFailover = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(requestUsageLineRepository.findUsageLogIdsWithFailover(ids));
        Map<UUID, List<String>> modelsByLog = ids.isEmpty() ? Map.of() : loadModelNames(ids);
        Map<UUID, String> emails = loadUserEmails(page.getContent());

        return PageResponse.fromPage(page, log -> {
            UUID userId = log.getUser() != null ? log.getUser().getId() : null;
            return UsageLogListItemResponse.builder()
                    .id(log.getId())
                    .requestId(log.getRequestId())
                    .purpose(log.getPurpose())
                    .status(log.getStatus())
                    .userId(userId)
                    .userEmail(userId != null ? emails.get(userId) : null)
                    .guestIp(log.getGuestIp())
                    .totalInputTokens(log.getTotalInputTokens())
                    .totalOutputTokens(log.getTotalOutputTokens())
                    .totalCostUsd(log.getTotalCostUsd())
                    .estimatedUnpricedCostUsd(log.getEstimatedUnpricedCostUsd())
                    .latencyMs(log.getLatencyMs())
                    .startedAt(log.getStartedAt())
                    .finishedAt(log.getFinishedAt())
                    .hasFailover(withFailover.contains(log.getId()))
                    .models(modelsByLog.getOrDefault(log.getId(), List.of()))
                    .build();
        });
    }

    /** Postgres' JDBC driver can't infer a bind parameter's type from a bare
     * {@code (:x IS NULL OR col >= :x)} pattern for timestamp columns ("could not determine data
     * type of parameter") - a dynamic {@link org.springframework.data.jpa.domain.Specification}
     * that only adds a predicate when the filter is non-null avoids the problem entirely, same
     * pattern as {@code AuditLogServiceImpl.buildSpec}. */
    private org.springframework.data.jpa.domain.Specification<RequestUsageLog> buildSpec(UsageLogSearchFilter filter) {
        return (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (filter.purpose() != null) {
                predicates.add(cb.equal(root.get("purpose"), filter.purpose()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("startedAt"), filter.to()));
            }
            // EXISTS rather than a join so one request with several matching lines stays one row.
            if (StringUtils.hasText(filter.provider()) || StringUtils.hasText(filter.model())) {
                var sub = cq.subquery(Integer.class);
                var line = sub.from(RequestUsageLine.class);
                List<jakarta.persistence.criteria.Predicate> linePredicates = new java.util.ArrayList<>();
                linePredicates.add(cb.equal(line.get("usageLog"), root));
                if (StringUtils.hasText(filter.provider())) {
                    linePredicates.add(cb.equal(line.get("provider"), filter.provider().trim()));
                }
                if (StringUtils.hasText(filter.model())) {
                    linePredicates.add(cb.equal(line.get("modelName"), filter.model().trim()));
                }
                sub.select(cb.literal(1)).where(linePredicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
                predicates.add(cb.exists(sub));
            }
            if (StringUtils.hasText(filter.userOrIp())) {
                String pattern = "%" + filter.userOrIp().trim().toLowerCase(java.util.Locale.ROOT) + "%";
                var user = root.join("user", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("guestIp")), pattern),
                        cb.like(cb.lower(user.get("email")), pattern)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    @Override
    @Transactional
    public UsageLogDetailResponse getDetail(UUID id) {
        RequestUsageLog log = requestUsageLogRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USAGE_LOG_NOT_FOUND));

        List<UsageLineResponse> lines = requestUsageLineRepository.findByUsageLogIdOrderBySeq(id).stream()
                .map(line -> UsageLineResponse.builder()
                        .seq(line.getSeq())
                        .nodeName(line.getNodeName())
                        .attempt(line.getAttempt())
                        .chatModelId(line.getChatModel() != null ? line.getChatModel().getId() : null)
                        .provider(line.getProvider())
                        .modelName(line.getModelName())
                        .sourceType(line.getSourceType())
                        .inputTokens(line.getInputTokens())
                        .outputTokens(line.getOutputTokens())
                        .cachedTokens(line.getCachedTokens())
                        .costUsd(line.getCostUsd())
                        .estimatedCostUsd(line.getEstimatedCostUsd())
                        .costStatus(line.getCostStatus())
                        .latencyMs(line.getLatencyMs())
                        .status(line.getStatus())
                        .errorCode(line.getErrorCode())
                        .occurredAt(line.getOccurredAt())
                        .build())
                .toList();

        String query = log.getUserMessage() != null ? log.getUserMessage().getContent() : null;
        String answer = log.getAssistantMessage() != null ? log.getAssistantMessage().getContent() : null;
        var citations = log.getAssistantMessage() != null ? log.getAssistantMessage().getCitations() : null;

        return UsageLogDetailResponse.builder()
                .id(log.getId())
                .requestId(log.getRequestId())
                .purpose(log.getPurpose())
                .status(log.getStatus())
                .userId(log.getUser() != null ? log.getUser().getId() : null)
                .userEmail(log.getUser() != null ? log.getUser().getEmail() : null)
                .guestIp(log.getGuestIp())
                .totalInputTokens(log.getTotalInputTokens())
                .totalOutputTokens(log.getTotalOutputTokens())
                .totalCachedTokens(log.getTotalCachedTokens())
                .totalCostUsd(log.getTotalCostUsd())
                .estimatedUnpricedCostUsd(log.getEstimatedUnpricedCostUsd())
                .unpricedLineCount(log.getUnpricedLineCount())
                .lineCount(log.getLineCount())
                .latencyMs(log.getLatencyMs())
                .startedAt(log.getStartedAt())
                .finishedAt(log.getFinishedAt())
                .query(query)
                .answer(answer)
                .citations(citations)
                .lines(lines)
                .build();
    }

    private Map<UUID, List<String>> loadModelNames(List<UUID> usageLogIds) {
        Map<UUID, java.util.TreeSet<String>> byLog = new HashMap<>();
        for (Object[] row : requestUsageLineRepository.findModelNamesByUsageLogIds(usageLogIds)) {
            byLog.computeIfAbsent((UUID) row[0], id -> new java.util.TreeSet<>()).add((String) row[1]);
        }
        Map<UUID, List<String>> result = new HashMap<>();
        byLog.forEach((id, names) -> result.put(id, List.copyOf(names)));
        return result;
    }

    /** Batch lookup - {@code log.getUser()} is a lazy proxy and this method runs outside a transaction. */
    private Map<UUID, String> loadUserEmails(List<RequestUsageLog> logs) {
        Set<UUID> userIds = new HashSet<>();
        for (RequestUsageLog log : logs) {
            if (log.getUser() != null) {
                userIds.add(log.getUser().getId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new HashMap<>();
        userRepository.findAllById(userIds).forEach(user -> emails.put(user.getId(), user.getEmail()));
        return emails;
    }

    private void validate(UsageLogIngestRequest request) {
        Map<String, String> errors = new HashMap<>();

        Set<Integer> seen = new HashSet<>();
        for (UsageLineIngestRequest line : request.lines()) {
            if (!seen.add(line.seq())) {
                errors.put("lines[seq=" + line.seq() + "]", "seq bị trùng trong cùng 1 request");
            }
            boolean requiresCostUsd = line.costStatus() == UsageCostStatus.PRICED;
            boolean hasCostUsd = line.costUsd() != null;
            if (requiresCostUsd != hasCostUsd) {
                errors.put("lines[seq=" + line.seq() + "].costUsd",
                        "costUsd bắt buộc khi costStatus = PRICED, ngược lại phải để trống");
            }
            if (line.status() == UsageRequestStatus.PARTIAL) {
                errors.put("lines[seq=" + line.seq() + "].status", "1 line chỉ được SUCCESS hoặc ERROR");
            }
        }

        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.USAGE_LOG_INVALID_PAYLOAD, errors);
        }
    }

    private RequestUsageLine toEntity(UsageLineIngestRequest line, RequestUsageLog logReference) {
        return RequestUsageLine.builder()
                .usageLog(logReference)
                .seq(line.seq())
                .nodeName(line.nodeName())
                .attempt(line.attempt())
                .chatModel(line.chatModelId() == null ? null
                        : com.unisage.backend.entity.ChatModel.builder().id(line.chatModelId()).build())
                .provider(line.provider())
                .modelName(line.modelName())
                .sourceType(line.sourceType())
                .inputTokens(line.inputTokens())
                .outputTokens(line.outputTokens())
                .cachedTokens(line.cachedTokens())
                .costUsd(line.costUsd())
                .estimatedCostUsd(line.estimatedCostUsd())
                .costStatus(line.costStatus())
                .latencyMs(line.latencyMs())
                .status(line.status())
                .errorCode(line.errorCode())
                .occurredAt(toUtc(line.occurredAt()))
                .build();
    }

    private static LocalDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static Integer latencyMs(OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return (int) Duration.between(startedAt, finishedAt).toMillis();
    }

    private record Aggregates(
            int totalInputTokens,
            int totalOutputTokens,
            int totalCachedTokens,
            BigDecimal totalCostUsd,
            BigDecimal estimatedUnpricedCostUsd,
            int unpricedLineCount) {

        static Aggregates of(List<UsageLineIngestRequest> lines) {
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedTokens = 0;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal estimatedUnpriced = BigDecimal.ZERO;
            int unpricedCount = 0;

            for (UsageLineIngestRequest line : lines) {
                inputTokens += line.inputTokens();
                outputTokens += line.outputTokens();
                cachedTokens += line.cachedTokens();
                if (line.costStatus() == UsageCostStatus.PRICED) {
                    totalCost = totalCost.add(line.costUsd());
                } else if (line.costStatus() == UsageCostStatus.UNPRICED) {
                    estimatedUnpriced = estimatedUnpriced.add(line.estimatedCostUsd());
                    unpricedCount++;
                }
            }

            return new Aggregates(inputTokens, outputTokens, cachedTokens, totalCost, estimatedUnpriced, unpricedCount);
        }
    }
}
