package com.unisage.backend.service.usagelimit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.enums.UsageLimitScope;
import com.unisage.backend.entity.enums.UsageLimitType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UsageLimitRepository;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UsageLimitServiceImpl implements UsageLimitService {

    private final UsageLimitRepository usageLimitRepository;
    private final SystemConfigResolver configResolver;

    @Value("${app.usage-limit.limit-type:MESSAGE}")
    private UsageLimitType limitType;

    @Value("${app.usage-limit.scope:DAILY}")
    private UsageLimitScope scope;

    @Override
    @Transactional
    public UsageLimit checkAndGetOrCreate(User user, GuestSession guestSession) {
        // Read live on every call (not cached in a field) so an admin toggling this in
        // System Settings takes effect on the very next request, no redeploy/restart needed.
        boolean enabled = configResolver.getBoolean("chat.usage_limit.enabled", false);
        if (!enabled) {
            return null;
        }
        if ((user == null) == (guestSession == null)) {
            throw new IllegalStateException("Exactly one of user or guestSession must be provided");
        }

        LocalDate scopeDate = resolveScopeDate(scope);
        int maxCount = user != null
                ? configResolver.getInt("chat.usage_limit.user_daily_limit", 30)
                : configResolver.getInt("chat.usage_limit.guest_daily_limit", 10);

        UsageLimit usageLimit = user != null
                ? usageLimitRepository
                        .findByUserIdAndLimitTypeAndScopeAndScopeDate(user.getId(), limitType, scope, scopeDate)
                        .orElseGet(() -> create(user, null, scopeDate))
                : usageLimitRepository
                        .findByGuestSessionIdAndLimitTypeAndScopeAndScopeDate(guestSession.getId(), limitType, scope, scopeDate)
                        .orElseGet(() -> create(null, guestSession, scopeDate));

        if (usageLimit.getUsedCount() >= maxCount) {
            throw new AppException(ErrorCode.USAGE_LIMIT_EXCEEDED, toErrors(usageLimit, maxCount));
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

    private UsageLimit create(User user, GuestSession guestSession, LocalDate scopeDate) {
        return usageLimitRepository.save(UsageLimit.builder()
                .user(user)
                .guestSession(guestSession)
                .limitType(limitType)
                .scope(scope)
                .scopeDate(scopeDate)
                .build());
    }

    private Map<String, String> toErrors(UsageLimit usageLimit, int maxCount) {
        Map<String, String> errors = new HashMap<>();
        errors.put("used", String.valueOf(usageLimit.getUsedCount()));
        errors.put("max", String.valueOf(maxCount));
        errors.put("resetAt", resolveResetAt(usageLimit.getScope(), usageLimit.getScopeDate()).toString());
        return errors;
    }

    /**
     * Chuẩn hoá "hôm nay" về điểm bắt đầu chu kỳ của scope, để mọi request
     * trong cùng chu kỳ (cùng ngày / cùng tuần / cùng tháng) trúng chung một row.
     */
    private LocalDate resolveScopeDate(UsageLimitScope scope) {
        LocalDate today = LocalDate.now();
        return switch (scope) {
            case DAILY -> today;
            case WEEKLY -> today.with(DayOfWeek.MONDAY);
            case MONTHLY -> today.withDayOfMonth(1);
        };
    }

    private LocalDateTime resolveResetAt(UsageLimitScope scope, LocalDate scopeDate) {
        return switch (scope) {
            case DAILY -> scopeDate.plusDays(1).atStartOfDay();
            case WEEKLY -> scopeDate.plusWeeks(1).atStartOfDay();
            case MONTHLY -> scopeDate.plusMonths(1).atStartOfDay();
        };
    }
}
