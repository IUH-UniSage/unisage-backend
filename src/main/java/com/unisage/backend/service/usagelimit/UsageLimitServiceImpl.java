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

    private final UsageLimitRepository usageLimitRepository;

    @Value("${app.usage-limit.enabled:false}")
    private boolean enabled;

    @Value("${app.usage-limit.user-daily-limit:30}")
    private int userDailyLimit;

    @Value("${app.usage-limit.guest-daily-limit:10}")
    private int guestDailyLimit;

    @Value("${app.usage-limit.limit-type:message}")
    private String limitType;

    @Value("${app.usage-limit.scope:daily}")
    private String scope;

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
        int maxCount = user != null ? userDailyLimit : guestDailyLimit;

        UsageLimit usageLimit = user != null
                ? usageLimitRepository
                        .findByUserIdAndLimitTypeAndScopeAndScopeDate(user.getId(), limitType, scope, today)
                        .orElseGet(() -> create(user, null, today))
                : usageLimitRepository
                        .findByIpAddressAndLimitTypeAndScopeAndScopeDate(ipAddress, limitType, scope, today)
                        .orElseGet(() -> create(null, ipAddress, today));

        // maxCount không lưu trong entity — luôn đọc lại từ properties nên đổi
        // config áp dụng ngay cho mọi row hiện có, không cần migrate dữ liệu cũ.
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

    private UsageLimit create(User user, String ipAddress, LocalDate scopeDate) {
        return usageLimitRepository.save(UsageLimit.builder()
                .user(user)
                .ipAddress(ipAddress)
                .limitType(limitType)
                .scope(scope)
                .scopeDate(scopeDate)
                .build());
    }

    private Map<String, String> toErrors(UsageLimit usageLimit, int maxCount) {
        Map<String, String> errors = new HashMap<>();
        errors.put("used", String.valueOf(usageLimit.getUsedCount()));
        errors.put("max", String.valueOf(maxCount));
        errors.put("resetAt", usageLimit.getScopeDate().plusDays(1).atStartOfDay().toString());
        return errors;
    }
}
