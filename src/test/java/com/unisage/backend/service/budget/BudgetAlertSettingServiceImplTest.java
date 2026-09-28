package com.unisage.backend.service.budget;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.unisage.backend.dto.request.BudgetAlertSettingRequest;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicitly overrides the Slack env properties rather than trusting whatever ambient .env a dev
 * machine happens to have (a real webhook is legitimately configured there for manual testing) -
 * without this override the "not configured" assertion below is only true by accident. */
class BudgetAlertSettingServiceImplTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void slackProps(DynamicPropertyRegistry registry) {
        registry.add("app.budget-alert.slack.enabled", () -> "false");
        registry.add("app.budget-alert.slack.webhook-url", () -> "");
        registry.add("app.budget-alert.slack.channel-label", () -> "");
    }

    @Autowired
    private BudgetAlertSettingService service;

    private BudgetAlertSettingRequest.BudgetAlertSettingRequestBuilder validBuilder() {
        return BudgetAlertSettingRequest.builder()
                .thresholdsPercent(List.of(50, 80, 100))
                .spikeDetectionEnabled(false)
                .spikeThresholdPercent(50)
                .inAppEnabled(true)
                .emailEnabled(false)
                .emailRecipients(List.of())
                .slackEnabled(false);
    }

    @Test
    void getReturnsSeededDefaults() {
        var response = service.get();
        assertThat(response.thresholdsPercent()).containsExactly(50, 80, 100);
        assertThat(response.inAppEnabled()).isTrue();
    }

    @Test
    void update_persistsAndReturnsNewValues() {
        var response = service.update(validBuilder().thresholdsPercent(List.of(90)).build());
        assertThat(response.thresholdsPercent()).containsExactly(90);
        assertThat(service.get().thresholdsPercent()).containsExactly(90);
    }

    @Test
    void thresholdOutOfRange_isRejected() {
        BudgetAlertSettingRequest request = validBuilder().thresholdsPercent(List.of(201)).build();

        assertThatThrownBy(() -> service.update(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_ALERT_SETTING_INVALID);
    }

    @Test
    void invalidEmail_isRejected() {
        BudgetAlertSettingRequest request = validBuilder()
                .emailEnabled(true)
                .emailRecipients(List.of("not-an-email"))
                .build();

        assertThatThrownBy(() -> service.update(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_ALERT_SETTING_INVALID);
    }

    @Test
    void emailEnabledWithNoRecipients_isRejected() {
        BudgetAlertSettingRequest request = validBuilder().emailEnabled(true).emailRecipients(List.of()).build();

        assertThatThrownBy(() -> service.update(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUDGET_ALERT_SETTING_INVALID);
    }

    @Test
    void slackNotConfiguredInEnv_reportsNotConfigured() {
        var response = service.get();
        assertThat(response.slackConfigured()).isFalse();
        assertThat(response.slackChannelLabel()).isNull();
    }
}
