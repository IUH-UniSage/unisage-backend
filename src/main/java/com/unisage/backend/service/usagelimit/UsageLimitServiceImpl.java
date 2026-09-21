package com.unisage.backend.service.usagelimit;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.UsageLimitResponse;
import com.unisage.backend.dto.response.UsageWindowResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.UsageLimitPlan;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.UsageLimitWindow;
import com.unisage.backend.entity.enums.UsageWindowStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UsageLimitPlanRepository;
import com.unisage.backend.repository.UsageLimitRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;
import com.unisage.backend.utils.TokenEstimator;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Token quota per identity, in two anchored windows (24h / 7d). The first counted request opens a
 * window; when it ends the counter starts again from zero at the next request.
 *
 * <p>Concurrency: the identity's counter rows are locked with SELECT ... FOR UPDATE (always DAILY
 * then WEEKLY) so first-time creation, window reset and token addition never run twice or overwrite
 * each other. Answers are counted only when they complete, so a request that starts before earlier
 * answers are counted can push usage past the limit; the next request is then blocked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageLimitServiceImpl implements UsageLimitService {

    private static final String USAGE_LIMIT_ENABLED_KEY = "chat.usage_limit.enabled";
    private static final List<UsageLimitWindow> WINDOWS = List.of(UsageLimitWindow.DAILY, UsageLimitWindow.WEEKLY);

    private final UsageLimitRepository usageLimitRepository;
    private final UsageLimitPlanRepository usageLimitPlanRepository;
    private final UserRepository userRepository;
    private final TokenEstimator tokenEstimator;
    private final Clock clock;
    private final SystemConfigResolver configResolver;

    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zoneId;

    @Override
    @Transactional
    public void checkAndConsumeQuestion(User user, GuestSession guestSession, String question) {
        if (!isEnabled()) {
            return;
        }
        requireExactlyOne(user, guestSession);

        UsageLimitPlan plan = resolvePlan(user);
        LocalDateTime now = LocalDateTime.now(clock);

        ensureRows(user, guestSession, now);
        List<UsageLimit> rows = lockRows(user, guestSession);

        rows.forEach(row -> resetIfExpired(row, now));

        UsageLimit exhausted = rows.stream()
                .filter(row -> isExhausted(row, plan))
                .max(Comparator.comparing((UsageLimit row) -> resetAt(row)))
                .orElse(null);
        if (exhausted != null) {
            throw new AppException(ErrorCode.USAGE_LIMIT_EXCEEDED, Map.of(
                    "window", exhausted.getWindowType().name(),
                    "resetAt", toZoned(resetAt(exhausted)).toString()));
        }

        long tokens = tokenEstimator.estimate(question);
        for (UsageLimit row : rows) {
            if (row.getWindowStart() == null) {
                row.setWindowStart(now);
            }
            row.setUsedTokens(row.getUsedTokens() + tokens);
        }
        usageLimitRepository.saveAll(rows);
    }

    @Override
    @Transactional
    public void consumeAnswer(User user, GuestSession guestSession, String answer) {
        if (!isEnabled()) {
            return;
        }
        requireExactlyOne(user, guestSession);

        long tokens = tokenEstimator.estimate(answer);
        if (tokens == 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<UsageLimit> rows = lockRows(user, guestSession);
        for (UsageLimit row : rows) {
            // A window that ended while the answer was being written no longer counts it.
            if (row.getWindowStart() != null && !isExpired(row, now)) {
                row.setUsedTokens(row.getUsedTokens() + tokens);
            }
        }
        usageLimitRepository.saveAll(rows);
    }

    @Override
    @Transactional
    public UsageLimitResponse getUsage(UUID userId, GuestSession guestSession) {
        if (userId != null && guestSession != null) {
            throw new IllegalStateException("At most one of user or guestSession may be provided");
        }
        if (!isEnabled()) {
            UsageWindowResponse unlimited = UsageWindowResponse.builder().status(UsageWindowStatus.UNLIMITED).build();
            return new UsageLimitResponse(unlimited, unlimited);
        }

        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        UsageLimitPlan plan = resolvePlan(user);
        LocalDateTime now = LocalDateTime.now(clock);

        Map<UsageLimitWindow, UsageLimit> rows = new HashMap<>();
        List<UsageLimit> existing = user != null
                ? usageLimitRepository.findByUserId(user.getId())
                : guestSession != null ? usageLimitRepository.findByGuestSessionId(guestSession.getId()) : List.of();
        existing.forEach(row -> rows.put(row.getWindowType(), row));

        return new UsageLimitResponse(
                toWindowResponse(UsageLimitWindow.DAILY, plan, rows.get(UsageLimitWindow.DAILY), now),
                toWindowResponse(UsageLimitWindow.WEEKLY, plan, rows.get(UsageLimitWindow.WEEKLY), now));
    }

    /** Read live on every call so an admin flipping it in System Settings applies to the next request. */
    private boolean isEnabled() {
        return configResolver.getBoolean(USAGE_LIMIT_ENABLED_KEY, true);
    }

    private UsageWindowResponse toWindowResponse(UsageLimitWindow window, UsageLimitPlan plan, UsageLimit row,
            LocalDateTime now) {
        Long limit = limitOf(plan, window);
        if (limit == null) {
            return UsageWindowResponse.builder().status(UsageWindowStatus.UNLIMITED).build();
        }
        if (row == null || row.getWindowStart() == null || isExpired(row, now)) {
            return UsageWindowResponse.builder().status(UsageWindowStatus.IDLE).remainingPercent(100).build();
        }
        long remaining = Math.max(0, limit - row.getUsedTokens());
        return UsageWindowResponse.builder()
                .status(UsageWindowStatus.ACTIVE)
                .remainingPercent((int) (remaining * 100 / limit))
                .resetAt(toZoned(resetAt(row)))
                .build();
    }

    /** The role's plan, else the default plan. A missing default is a configuration fault, never "unlimited". */
    private UsageLimitPlan resolvePlan(User user) {
        if (user != null && user.getRole() != null && user.getRole().getUsageLimitPlan() != null) {
            return user.getRole().getUsageLimitPlan();
        }
        return usageLimitPlanRepository.findFirstByIsDefaultTrue().orElseThrow(() -> {
            log.error("No default usage limit plan exists; refusing to run chat without a limit");
            return new AppException(ErrorCode.USAGE_LIMIT_PLAN_MISSING);
        });
    }

    private void ensureRows(User user, GuestSession guestSession, LocalDateTime now) {
        for (UsageLimitWindow window : WINDOWS) {
            if (user != null) {
                usageLimitRepository.insertUserWindowIfAbsent(UUID.randomUUID(), now, user.getId(), window.name());
            } else {
                usageLimitRepository.insertGuestWindowIfAbsent(UUID.randomUUID(), now, guestSession.getId(), window.name());
            }
        }
    }

    private List<UsageLimit> lockRows(User user, GuestSession guestSession) {
        return user != null
                ? usageLimitRepository.lockByUserId(user.getId())
                : usageLimitRepository.lockByGuestSessionId(guestSession.getId());
    }

    private void resetIfExpired(UsageLimit row, LocalDateTime now) {
        if (row.getWindowStart() != null && isExpired(row, now)) {
            row.setWindowStart(null);
            row.setUsedTokens(0L);
        }
    }

    private boolean isExhausted(UsageLimit row, UsageLimitPlan plan) {
        Long limit = limitOf(plan, row.getWindowType());
        return limit != null && row.getWindowStart() != null && row.getUsedTokens() >= limit;
    }

    private boolean isExpired(UsageLimit row, LocalDateTime now) {
        return !now.isBefore(resetAt(row));
    }

    private LocalDateTime resetAt(UsageLimit row) {
        return row.getWindowStart().plus(row.getWindowType().getDuration());
    }

    private Long limitOf(UsageLimitPlan plan, UsageLimitWindow window) {
        return window == UsageLimitWindow.DAILY ? plan.getDailyTokenLimit() : plan.getWeeklyTokenLimit();
    }

    /** Stored times are UTC; callers see the configured zone (Vietnam by default). */
    private OffsetDateTime toZoned(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zoneId).toOffsetDateTime();
    }

    private void requireExactlyOne(User user, GuestSession guestSession) {
        if ((user == null) == (guestSession == null)) {
            throw new IllegalStateException("Exactly one of user or guestSession must be provided");
        }
    }
}
