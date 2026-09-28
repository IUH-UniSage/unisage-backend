package com.unisage.backend.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/** Range (1-200) and email format are validated in the service, not here - the error map needs to
 * name which threshold/email is invalid (plan.md "BudgetAlertSetting"). */
@Builder
public record BudgetAlertSettingRequest(
    @NotEmpty(message = "thresholdsPercent không được để trống")
    List<Integer> thresholdsPercent,

    @NotNull(message = "spikeDetectionEnabled không được để trống")
    Boolean spikeDetectionEnabled,

    @NotNull(message = "spikeThresholdPercent không được để trống")
    Integer spikeThresholdPercent,

    @NotNull(message = "inAppEnabled không được để trống")
    Boolean inAppEnabled,

    @NotNull(message = "emailEnabled không được để trống")
    Boolean emailEnabled,

    List<String> emailRecipients,

    @NotNull(message = "slackEnabled không được để trống")
    Boolean slackEnabled
) {}
