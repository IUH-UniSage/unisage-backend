package com.unisage.backend.service.budget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.BudgetAlertSettingRequest;
import com.unisage.backend.dto.response.BudgetAlertSettingResponse;
import com.unisage.backend.entity.BudgetAlertSetting;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.BudgetAlertSettingRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

/**
 * Singleton row (id = 1, seeded by V26) - only GET/PUT exist, matching plan.md's "no POST/DELETE"
 * rule for this resource.
 */
@Service
@RequiredArgsConstructor
public class BudgetAlertSettingServiceImpl implements BudgetAlertSettingService {

    private static final short SINGLETON_ID = 1;
    private static final int MIN_THRESHOLD_PERCENT = 1;
    private static final int MAX_THRESHOLD_PERCENT = 200;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final BudgetAlertSettingRepository repository;

    @Value("${app.budget-alert.slack.enabled:false}")
    private boolean slackEnabledEnv;

    @Value("${app.budget-alert.slack.webhook-url:}")
    private String slackWebhookUrl;

    @Value("${app.budget-alert.slack.channel-label:}")
    private String slackChannelLabel;

    @Override
    public BudgetAlertSettingResponse get() {
        return mapToResponse(loadSingleton());
    }

    @Override
    @Transactional
    public BudgetAlertSettingResponse update(BudgetAlertSettingRequest request) {
        validate(request);

        BudgetAlertSetting setting = loadSingleton();
        setting.setThresholdsPercent(request.thresholdsPercent().toArray(new Integer[0]));
        setting.setSpikeDetectionEnabled(request.spikeDetectionEnabled());
        setting.setSpikeThresholdPercent(request.spikeThresholdPercent());
        setting.setInAppEnabled(request.inAppEnabled());
        setting.setEmailEnabled(request.emailEnabled());
        setting.setEmailRecipients(
                request.emailRecipients() == null ? new String[0] : request.emailRecipients().toArray(new String[0]));
        setting.setSlackEnabled(request.slackEnabled());

        setting = repository.save(setting);
        return mapToResponse(setting);
    }

    private BudgetAlertSetting loadSingleton() {
        return repository.findById(SINGLETON_ID).orElseThrow(() -> new IllegalStateException(
                "budget_alert_settings singleton row missing - V26 should have seeded id=1"));
    }

    private void validate(BudgetAlertSettingRequest request) {
        Map<String, String> errors = new HashMap<>();

        for (Integer threshold : request.thresholdsPercent()) {
            if (threshold == null || threshold < MIN_THRESHOLD_PERCENT || threshold > MAX_THRESHOLD_PERCENT) {
                errors.put("thresholdsPercent", "Mỗi ngưỡng phải trong khoảng 1-200");
                break;
            }
        }

        Integer spike = request.spikeThresholdPercent();
        if (spike < MIN_THRESHOLD_PERCENT || spike > MAX_THRESHOLD_PERCENT) {
            errors.put("spikeThresholdPercent", "Phải trong khoảng 1-200");
        }

        List<String> recipients = request.emailRecipients();
        if (Boolean.TRUE.equals(request.emailEnabled()) && (recipients == null || recipients.isEmpty())) {
            errors.put("emailRecipients", "Bắt buộc có ít nhất 1 email khi bật cảnh báo email");
        }
        if (recipients != null) {
            for (String email : recipients) {
                if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
                    errors.put("emailRecipients", "Email không hợp lệ: " + email);
                    break;
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.BUDGET_ALERT_SETTING_INVALID, errors);
        }
    }

    private BudgetAlertSettingResponse mapToResponse(BudgetAlertSetting setting) {
        boolean slackConfigured = slackEnabledEnv && slackWebhookUrl != null && !slackWebhookUrl.isBlank();
        return BudgetAlertSettingResponse.builder()
                .thresholdsPercent(List.of(setting.getThresholdsPercent()))
                .spikeDetectionEnabled(setting.getSpikeDetectionEnabled())
                .spikeThresholdPercent(setting.getSpikeThresholdPercent())
                .inAppEnabled(setting.getInAppEnabled())
                .emailEnabled(setting.getEmailEnabled())
                .emailRecipients(List.of(setting.getEmailRecipients()))
                .slackEnabled(setting.getSlackEnabled())
                .slackConfigured(slackConfigured)
                .slackChannelLabel(slackConfigured && !slackChannelLabel.isBlank() ? slackChannelLabel : null)
                .updatedAt(setting.getUpdatedAt())
                .build();
    }
}
