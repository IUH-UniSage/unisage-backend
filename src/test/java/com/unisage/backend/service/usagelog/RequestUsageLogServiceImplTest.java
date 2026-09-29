package com.unisage.backend.service.usagelog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.dto.request.internal.UsageLineIngestRequest;
import com.unisage.backend.dto.request.internal.UsageLogIngestRequest;
import com.unisage.backend.entity.enums.UsageCostStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies timezone-cut day grouping, hasFailover flag, null-message detail, and
 * cost aggregation across purpose/provider/model/day groupBy. */
class RequestUsageLogServiceImplTest extends PostgresIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private RequestUsageLogService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM request_usage_lines");
        jdbcTemplate.update("DELETE FROM request_usage_logs");
    }

    private UsageLineIngestRequest line(int seq, String provider, String model, OffsetDateTime occurredAt,
            int attempt, UsageRequestStatus status) {
        return new UsageLineIngestRequest(seq, "GenerationSynthesisNode", attempt, null, provider, model,
                com.unisage.backend.entity.enums.ChatModelSourceType.CLOUD_API, 100, 50, 0,
                status == UsageRequestStatus.SUCCESS ? BigDecimal.valueOf(0.01) : null,
                BigDecimal.valueOf(0.01), status == UsageRequestStatus.SUCCESS ? UsageCostStatus.PRICED : UsageCostStatus.UNPRICED,
                50, status, status == UsageRequestStatus.ERROR ? "PROVIDER_ERROR" : null, occurredAt);
    }

    private UUID ingestChat(OffsetDateTime startedAt, String provider, List<UsageLineIngestRequest> lines) {
        var response = service.ingest(new UsageLogIngestRequest(
                UUID.randomUUID(), UsagePurpose.CHAT, null, null, null, null, null,
                UsageRequestStatus.SUCCESS, startedAt, startedAt, lines));
        return response.id();
    }

    @Test
    void dayGroupBy_splitsRecordsAcrossTheTimezoneBoundary() {
        // 23:59:59 and 00:00:01 in Asia/Ho_Chi_Minh, one second apart in wall-clock VN time but
        // straddling midnight - must land in 2 different day buckets.
        OffsetDateTime lateNight = OffsetDateTime.of(2026, 9, 27, 23, 59, 59, 0, VN.getRules().getOffset(
                java.time.LocalDateTime.of(2026, 9, 27, 23, 59, 59)));
        OffsetDateTime justAfterMidnight = OffsetDateTime.of(2026, 9, 28, 0, 0, 1, 0, VN.getRules().getOffset(
                java.time.LocalDateTime.of(2026, 9, 28, 0, 0, 1)));

        ingestChat(lateNight, "openai", List.of(line(0, "openai", "gpt-4o-mini", lateNight, 0, UsageRequestStatus.SUCCESS)));
        ingestChat(justAfterMidnight, "openai",
                List.of(line(0, "openai", "gpt-4o-mini", justAfterMidnight, 0, UsageRequestStatus.SUCCESS)));

        var from = lateNight.minusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var to = justAfterMidnight.plusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        var summary = service.summary(from, to, "day", null, null);

        assertThat(summary.buckets()).extracting(b -> b.key()).containsExactly("2026-09-27", "2026-09-28");
    }

    @Test
    void searchListsHasFailoverFlagCorrectly() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID failedOverId = ingestChat(now, "openai", List.of(
                line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.ERROR),
                line(1, "anthropic", "claude-3-haiku", now, 1, UsageRequestStatus.SUCCESS)));
        UUID cleanId = ingestChat(now, "openai", List.of(line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.SUCCESS)));

        var page = service.search(null, null, now.minusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                now.plusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), Pageable.unpaged());

        var byId = page.data().stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.id(), item -> item));
        assertThat(byId.get(failedOverId).hasFailover()).isTrue();
        assertThat(byId.get(cleanId).hasFailover()).isFalse();
    }

    @Test
    void getDetail_returnsNullMessageFieldsWithoutError() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID id = ingestChat(now, "openai", List.of(line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.SUCCESS)));

        var detail = service.getDetail(id);

        assertThat(detail.query()).isNull();
        assertThat(detail.answer()).isNull();
        assertThat(detail.citations()).isNull();
        assertThat(detail.lines()).hasSize(1);
    }

    @Test
    void getDetail_unknownId_throwsUsageLogNotFound() {
        assertThatThrownBy(() -> service.getDetail(UUID.randomUUID()))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.USAGE_LOG_NOT_FOUND);
    }

    @Test
    void summaryByProvider_splitsAcrossProvidersWithinOneRequest() {
        OffsetDateTime now = OffsetDateTime.now();
        ingestChat(now, "openai", List.of(
                line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.ERROR),
                line(1, "anthropic", "claude-3-haiku", now, 1, UsageRequestStatus.SUCCESS)));

        var summary = service.summary(now.minusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                now.plusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), "provider", null, null);

        assertThat(summary.buckets()).extracting(b -> b.key()).contains("anthropic");
    }

    @Test
    void summaryByDay_providerFilter_excludesRequestsThatNeverTouchedThatProvider() {
        OffsetDateTime now = OffsetDateTime.now();
        ingestChat(now, "openai", List.of(line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.SUCCESS)));
        ingestChat(now, "anthropic", List.of(line(0, "anthropic", "claude-3-haiku", now, 0, UsageRequestStatus.SUCCESS)));

        var summary = service.summary(now.minusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                now.plusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), "day", null, "anthropic");

        assertThat(summary.buckets()).hasSize(1);
        assertThat(summary.buckets().get(0).requestCount()).isEqualTo(1);
    }

    @Test
    void summaryByProvider_purposeFilter_excludesOtherPurposes() {
        OffsetDateTime now = OffsetDateTime.now();
        ingestChat(now, "openai", List.of(line(0, "openai", "gpt-4o-mini", now, 0, UsageRequestStatus.SUCCESS)));

        var summary = service.summary(now.minusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                now.plusHours(1).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), "provider",
                UsagePurpose.EMBEDDING, null);

        assertThat(summary.buckets()).isEmpty();
    }
}
