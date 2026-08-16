package com.unisage.backend.service.usagelimit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.User;
import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UsageLimitRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UsageLimitServiceImpl implements UsageLimitService {

    private static final String LIMIT_TYPE_MESSAGE = "message";
    private static final String SCOPE_DAILY = "daily";

    private final UsageLimitRepository usageLimitRepository;

    @Value("${app.usage-limit.enabled:false}")
    private boolean enabled;

    @Value("${app.usage-limit.user-daily-limit:30}")
    private int userDailyLimit;

    @Value("${app.usage-limit.guest-daily-limit:10}")
    private int guestDailyLimit;

    @Override
    @Transactional
    public UsageLimit checkAndGetOrCreate(User user, String ipAddress) {
        if (!enabled) {
            return null;
        }
        if ((user == null) == (ipAddress == null)) {
            throw new IllegalStateException("Exactly one of user or ipAddress must be provided");
        }

        LocalDate today = LocalDate.now();
        UsageLimit usageLimit = user != null
                ? usageLimitRepository
                        .findByUserIdAndLimitTypeAndScopeAndScopeDate(user.getId(), LIMIT_TYPE_MESSAGE, SCOPE_DAILY, today)
                        .orElseGet(() -> create(user, null, today, userDailyLimit))
                : usageLimitRepository
                        .findByIpAddressAndLimitTypeAndScopeAndScopeDate(ipAddress, LIMIT_TYPE_MESSAGE, SCOPE_DAILY, today)
                        .orElseGet(() -> create(null, ipAddress, today, guestDailyLimit));

        if (usageLimit.getUsedCount() >= usageLimit.getMaxCount()) {
            throw new AppException(ErrorCode.USAGE_LIMIT_EXCEEDED, toErrors(usageLimit));
        }

        return usageLimit;
    }

    @Override
    @Transactional
    public void increment(UsageLimit usageLimit) {
        if (usageLimit == null) {
            return;
        }
        usageLimit.setUsedCount(usageLimit.getUsedCount() + 1);
        usageLimit.setLastUsedAt(LocalDateTime.now());
        usageLimitRepository.save(usageLimit);
    }

    private UsageLimit create(User user, String ipAddress, LocalDate scopeDate, int maxCount) {
        return usageLimitRepository.save(UsageLimit.builder()
                .user(user)
                .ipAddress(ipAddress)
                .limitType(LIMIT_TYPE_MESSAGE)
                .scope(SCOPE_DAILY)
                .scopeDate(scopeDate)
                .maxCount(maxCount)
                .build());
    }

    private Map<String, String> toErrors(UsageLimit usageLimit) {
        Map<String, String> errors = new HashMap<>();
        errors.put("used", String.valueOf(usageLimit.getUsedCount()));
        errors.put("max", String.valueOf(usageLimit.getMaxCount()));
        errors.put("resetAt", usageLimit.getScopeDate().plusDays(1).atStartOfDay().toString());
        return errors;
    }
}
