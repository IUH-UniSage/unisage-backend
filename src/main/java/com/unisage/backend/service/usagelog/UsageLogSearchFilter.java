package com.unisage.backend.service.usagelog;

import java.time.LocalDateTime;

import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;

/** All fields optional; {@code userOrIp} is a case-insensitive substring of the user's email or the guest IP. */
public record UsageLogSearchFilter(
    UsagePurpose purpose,
    UsageRequestStatus status,
    LocalDateTime from,
    LocalDateTime to,
    String provider,
    String model,
    String userOrIp
) {}
