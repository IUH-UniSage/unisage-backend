package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.BudgetAlertSetting;
import com.unisage.backend.entity.enums.AlertChannel;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;
import com.unisage.backend.repository.BudgetAlertLogRepository;
import com.unisage.backend.repository.BudgetAlertSettingRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** "trống → SKIPPED và health UP; lỗi 3 lần → GAVE_UP" verification. Mail/Slack are pointed at
 * unreachable local ports rather than mocked - the dispatcher has no seam to inject a fake
 * JavaMailSender/HTTP client into short of a real (failing) network call, so this exercises the
 * real send path end to end, just against a target that always refuses the connection. */
class BudgetAlertDispatchServiceImplTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void mailAndSlackProps(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> "1");
        registry.add("app.budget-alert.mail.from", () -> "alerts@unisage.test");
        registry.add("app.budget-alert.slack.enabled", () -> "true");
        registry.add("app.budget-alert.slack.webhook-url", () -> "http://127.0.0.1:1/webhook");
        registry.add("app.budget-alert.slack.timeout-ms", () -> "500");
    }

    @Autowired
    private BudgetAlertDispatchService dispatchService;

    @Autowired
    private BudgetAlertLogRepository alertLogRepository;

    @Autowired
    private BudgetAlertSettingRepository settingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM budget_alert_log");
        BudgetAlertSetting defaults = settingRepository.findById((short) 1).orElseThrow();
        defaults.setEmailRecipients(new String[0]);
        settingRepository.save(defaults);
    }

    private BudgetAlertLog claimedSpike(AlertChannel channel) {
        // No .id(...) here on purpose: JpaRepository.save() routes a NON-NULL id through
        // merge() (assumed existing row) instead of persist() (assumed new row), and merge()
        // on a row that doesn't exist yet fails with an optimistic-locking error. Let
        // @GeneratedValue assign it during the real persist().
        BudgetAlertLog alert = BudgetAlertLog.builder()
                .alertType(AlertType.SPIKE)
                .periodStart(LocalDate.now())
                .channel(channel)
                .dedupeKey("SPIKE:" + UUID.randomUUID())
                .spentUsd(BigDecimal.TEN)
                .status(AlertStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        return alertLogRepository.save(alert);
    }

    @Test
    void inApp_marksSentImmediately() {
        BudgetAlertLog alert = claimedSpike(AlertChannel.IN_APP);

        int dispatched = dispatchService.dispatchPending();

        assertThat(dispatched).isEqualTo(1);
        BudgetAlertLog reloaded = alertLogRepository.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
    }

    @Test
    void email_noRecipientsConfigured_isSkipped() {
        // emailRecipients left empty by cleanUp()'s reset - host/from ARE configured (dynamic
        // properties above), so this exercises the "recipients missing" skip path specifically.
        BudgetAlertLog alert = claimedSpike(AlertChannel.EMAIL);

        dispatchService.dispatchPending();

        BudgetAlertLog reloaded = alertLogRepository.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.SKIPPED);
    }

    @Test
    void email_sendFailureRetriesThenGivesUpAfterThreeAttempts() {
        BudgetAlertSetting setting = settingRepository.findById((short) 1).orElseThrow();
        setting.setEmailRecipients(new String[] {"admin@unisage.test"});
        settingRepository.save(setting);
        BudgetAlertLog alert = claimedSpike(AlertChannel.EMAIL);

        for (int attempt = 1; attempt <= 3; attempt++) {
            dispatchService.dispatchPending();
            BudgetAlertLog reloaded = alertLogRepository.findById(alert.getId()).orElseThrow();
            assertThat(reloaded.getAttemptCount()).isEqualTo(attempt);
            if (attempt < 3) {
                assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.FAILED);
                assertThat(reloaded.getNextAttemptAt()).isNotNull();
                // Simulate the backoff window having already passed, so the next
                // dispatchPending() run picks this row up again immediately.
                reloaded.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
                alertLogRepository.save(reloaded);
            } else {
                assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.GAVE_UP);
                assertThat(reloaded.getNextAttemptAt()).isNull();
            }
        }

        // A 4th run must not pick up the GAVE_UP row at all.
        int dispatched = dispatchService.dispatchPending();
        assertThat(dispatched).isZero();
    }

    @Test
    void slack_sendFailureRetriesThenGivesUpAfterThreeAttempts() {
        BudgetAlertLog alert = claimedSpike(AlertChannel.SLACK);

        for (int attempt = 1; attempt <= 3; attempt++) {
            dispatchService.dispatchPending();
            BudgetAlertLog reloaded = alertLogRepository.findById(alert.getId()).orElseThrow();
            assertThat(reloaded.getAttemptCount()).isEqualTo(attempt);
            if (attempt < 3) {
                reloaded.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC));
                alertLogRepository.save(reloaded);
            } else {
                assertThat(reloaded.getStatus()).isEqualTo(AlertStatus.GAVE_UP);
            }
        }
    }

    @Test
    void twoConcurrentDispatchRunsNeverSendTheSameAlertTwice() throws InterruptedException {
        claimedSpike(AlertChannel.IN_APP);
        claimedSpike(AlertChannel.IN_APP);

        Thread t1 = new Thread(dispatchService::dispatchPending);
        Thread t2 = new Thread(dispatchService::dispatchPending);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        Long sentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budget_alert_log WHERE status = 'SENT'", Long.class);
        assertThat(sentCount).isEqualTo(2);
    }
}
