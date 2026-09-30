package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;
import com.unisage.backend.repository.BudgetAlertLogRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAlertLogServiceImplTest extends PostgresIntegrationTest {

    @Autowired
    private BudgetAlertLogService service;

    @Autowired
    private BudgetAlertLogRepository alertLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM budget_alert_log");
    }

    private BudgetAlertLog spike(AlertChannel channel, AlertStatus status) {
        // No .id(...) here on purpose: JpaRepository.save() routes a NON-NULL id through
        // merge() (assumed existing row) instead of persist() (assumed new row).
        BudgetAlertLog alert = BudgetAlertLog.builder()
                .alertType(AlertType.SPIKE)
                .periodStart(LocalDate.now())
                .channel(channel)
                .dedupeKey("SPIKE:" + UUID.randomUUID())
                .spentUsd(BigDecimal.TEN)
                .status(status)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        return alertLogRepository.save(alert);
    }

    @Test
    void getAll_noFilters_returnsEverythingNewestFirst() {
        spike(AlertChannel.IN_APP, AlertStatus.SENT);
        spike(AlertChannel.EMAIL, AlertStatus.PENDING);

        var page = service.getAll(PageRequest.of(0, 10), null, null, null);

        assertThat(page.data()).hasSize(2);
        assertThat(page.totalItems()).isEqualTo(2);
    }

    @Test
    void getAll_statusFilter_excludesOtherStatuses() {
        spike(AlertChannel.IN_APP, AlertStatus.SENT);
        spike(AlertChannel.EMAIL, AlertStatus.PENDING);

        var page = service.getAll(PageRequest.of(0, 10), null, null, AlertStatus.PENDING);

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).channel()).isEqualTo(AlertChannel.EMAIL);
    }

    @Test
    void getAll_channelFilter_excludesOtherChannels() {
        spike(AlertChannel.IN_APP, AlertStatus.SENT);
        spike(AlertChannel.SLACK, AlertStatus.SENT);

        var page = service.getAll(PageRequest.of(0, 10), null, AlertChannel.SLACK, null);

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).channel()).isEqualTo(AlertChannel.SLACK);
    }
}
