package com.unisage.backend.service.usagelog;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.UsageLineIngestRequest;
import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.dto.response.internal.UsageLogIngestResponse;
import com.unisage.backend.entity.RequestUsageLine;
import com.unisage.backend.entity.RequestUsageLog;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.RequestUsageLineRepository;

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

    private final JdbcTemplate jdbcTemplate;
    private final RequestUsageLineRepository requestUsageLineRepository;

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
