package com.unisage.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Builder;

/** {@code slackConfigured}/{@code slackChannelLabel} are read from env, never from the DB row -
 * the webhook URL itself is never returned by any API (plan.md "BudgetAlertSetting"). */
@Builder
public record BudgetAlertSettingResponse(
    List<Integer> thresholdsPercent,
    Boolean spikeDetectionEnabled,
    Integer spikeThresholdPercent,
    Boolean inAppEnabled,
    Boolean emailEnabled,
    List<String> emailRecipients,
    Boolean slackEnabled,
    Boolean slackConfigured,
    String slackChannelLabel,
    LocalDateTime updatedAt
) {}
