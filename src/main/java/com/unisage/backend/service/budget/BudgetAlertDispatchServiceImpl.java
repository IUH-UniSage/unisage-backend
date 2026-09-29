package com.unisage.backend.service.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.unisage.backend.entity.Budget;
import com.unisage.backend.entity.BudgetAlertLog;
import com.unisage.backend.entity.BudgetAlertSetting;
import com.unisage.backend.entity.enums.AlertStatus;
import com.unisage.backend.entity.enums.AlertType;
import com.unisage.backend.integration.SlackWebhookClient;
import com.unisage.backend.repository.BudgetAlertLogRepository;
import com.unisage.backend.repository.BudgetAlertSettingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetAlertDispatchServiceImpl implements BudgetAlertDispatchService {

    private static final short SINGLETON_ID = 1;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_LIMIT = 50;

    private final BudgetAlertLogRepository alertLogRepository;
    private final BudgetAlertSettingRepository settingRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SlackWebhookClient slackWebhookClient;
    private final Clock clock;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.budget-alert.mail.from:}")
    private String mailFrom;

    @Value("${app.frontend-url:}")
    private String frontendUrl;

    @Override
    @Transactional
    public int dispatchPending() {
        List<BudgetAlertLog> claimed = alertLogRepository.claimReadyToSend(BATCH_LIMIT);
        for (BudgetAlertLog alert : claimed) {
            dispatchOne(alert);
        }
        if (!claimed.isEmpty()) {
            log.info("dispatchPending: processed {} alert(s)", claimed.size());
        }
        return claimed.size();
    }

    private void dispatchOne(BudgetAlertLog alert) {
        try {
            switch (alert.getChannel()) {
                case IN_APP -> markSent(alert);
                case EMAIL -> sendEmail(alert);
                case SLACK -> sendSlack(alert);
            }
        } catch (Exception exc) {
            recordFailure(alert, exc.getMessage());
        }
    }

    private void sendEmail(BudgetAlertLog alert) {
        if (!StringUtils.hasText(mailHost) || !StringUtils.hasText(mailFrom)) {
            markSkipped(alert);
            return;
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        BudgetAlertSetting setting = settingRepository.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("budget_alert_settings singleton row is missing"));
        String[] recipients = setting.getEmailRecipients();
        if (sender == null || recipients == null || recipients.length == 0) {
            markSkipped(alert);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipients);
        message.setSubject("UniSage - Cảnh báo ngân sách AI");
        message.setText(buildMessage(alert));
        sender.send(message);
        markSent(alert);
    }

    private void sendSlack(BudgetAlertLog alert) {
        if (!slackWebhookClient.isConfigured()) {
            markSkipped(alert);
            return;
        }
        slackWebhookClient.send(buildMessage(alert));
        markSent(alert);
    }

    private void markSent(BudgetAlertLog alert) {
        alert.setStatus(AlertStatus.SENT);
        alert.setSentAt(LocalDateTime.now(clock));
        alertLogRepository.save(alert);
    }

    private void markSkipped(BudgetAlertLog alert) {
        alert.setStatus(AlertStatus.SKIPPED);
        alertLogRepository.save(alert);
    }

    private void recordFailure(BudgetAlertLog alert, String errorMessage) {
        int attempts = alert.getAttemptCount() + 1;
        alert.setAttemptCount(attempts);
        alert.setLastAttemptAt(LocalDateTime.now(clock));
        alert.setErrorMessage(errorMessage);
        if (attempts >= MAX_ATTEMPTS) {
            alert.setStatus(AlertStatus.GAVE_UP);
            alert.setNextAttemptAt(null);
        } else {
            alert.setStatus(AlertStatus.FAILED);
            alert.setNextAttemptAt(LocalDateTime.now(clock).plusMinutes(1L << attempts));
        }
        alertLogRepository.save(alert);
    }

    private String buildMessage(BudgetAlertLog alert) {
        StringBuilder message = new StringBuilder();
        if (alert.getAlertType() == AlertType.SPIKE) {
            message.append("[UniSage] Phát hiện chi tiêu tăng đột biến ngày ")
                    .append(alert.getPeriodStart())
                    .append(": $").append(alert.getSpentUsd());
        } else {
            Budget budget = alert.getBudget();
            String scopeLabel = budget != null ? describeScope(budget) : "Ngân sách";
            BigDecimal percent = percentOf(alert.getSpentUsd(), alert.getLimitUsd());
            message.append("[UniSage] ").append(scopeLabel).append(" đã dùng ")
                    .append(percent.setScale(0, RoundingMode.HALF_UP)).append("% (")
                    .append(alert.getSpentUsd()).append("/").append(alert.getLimitUsd())
                    .append(" USD) trong kỳ bắt đầu ").append(alert.getPeriodStart());
        }
        if (StringUtils.hasText(frontendUrl)) {
            message.append(" - Xem chi tiết: ").append(frontendUrl).append("/cost-management");
        }
        return message.toString();
    }

    private static BigDecimal percentOf(BigDecimal spent, BigDecimal limit) {
        if (limit == null || limit.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return spent.divide(limit, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private static String describeScope(Budget budget) {
        return switch (budget.getScope()) {
            case SYSTEM -> "Ngân sách hệ thống";
            case PROVIDER -> "Ngân sách nhà cung cấp " + budget.getScopeProvider();
            case PURPOSE -> "Ngân sách mục đích " + budget.getScopePurpose();
        };
    }
}
