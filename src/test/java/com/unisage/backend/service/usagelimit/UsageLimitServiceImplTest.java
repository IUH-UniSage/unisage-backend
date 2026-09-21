package com.unisage.backend.service.usagelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.response.UsageLimitResponse;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageLimitServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 21, 3, 0);

    private UsageLimitRepository usageLimitRepository;
    private UsageLimitPlanRepository planRepository;
    private UserRepository userRepository;
    private SystemConfigResolver configResolver;
    private MutableClock clock;
    private UsageLimitServiceImpl service;

    private final UsageLimitPlan defaultPlan = plan("Mặc định", 1000L, 5000L, true);
    private final User user = User.builder().id(UUID.randomUUID()).role(Role.builder().name("USER").build()).build();
    private final GuestSession guest = GuestSession.builder().id(UUID.randomUUID()).build();

    private UsageLimit daily;
    private UsageLimit weekly;

    @BeforeEach
    void setUp() {
        usageLimitRepository = mock(UsageLimitRepository.class);
        planRepository = mock(UsageLimitPlanRepository.class);
        clock = new MutableClock(T0);
        userRepository = mock(UserRepository.class);
        configResolver = mock(SystemConfigResolver.class);
        when(configResolver.getBoolean(eq("chat.usage_limit.enabled"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        service = new UsageLimitServiceImpl(usageLimitRepository, planRepository, userRepository, new TokenEstimator(),
                clock, configResolver);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(service, "zoneId", ZoneId.of("Asia/Ho_Chi_Minh"));

        when(planRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(defaultPlan));

        daily = row(UsageLimitWindow.DAILY);
        weekly = row(UsageLimitWindow.WEEKLY);
        when(usageLimitRepository.lockByUserId(user.getId())).thenAnswer(i -> List.of(daily, weekly));
        when(usageLimitRepository.lockByGuestSessionId(guest.getId())).thenAnswer(i -> List.of(daily, weekly));
        when(usageLimitRepository.findByUserId(user.getId())).thenAnswer(i -> List.of(daily, weekly));
        when(usageLimitRepository.findByGuestSessionId(guest.getId())).thenAnswer(i -> List.of(daily, weekly));
    }

    // ── checkAndConsumeQuestion ──────────────────────────────────────────

    @Test
    void question_disabled_doesNothing() {
        when(configResolver.getBoolean(eq("chat.usage_limit.enabled"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);

        service.checkAndConsumeQuestion(user, null, "hello");

        verifyNoInteractions(usageLimitRepository, planRepository);
    }

    @Test
    void question_firstRequest_createsBothRowsOpensWindowsAndCountsTokens() {
        service.checkAndConsumeQuestion(user, null, "abcabcabc"); // 3 tokens

        verify(usageLimitRepository).insertUserWindowIfAbsent(any(), eq(T0), eq(user.getId()), eq("DAILY"));
        verify(usageLimitRepository).insertUserWindowIfAbsent(any(), eq(T0), eq(user.getId()), eq("WEEKLY"));
        assertThat(daily.getWindowStart()).isEqualTo(T0);
        assertThat(weekly.getWindowStart()).isEqualTo(T0);
        assertThat(daily.getUsedTokens()).isEqualTo(3);
        assertThat(weekly.getUsedTokens()).isEqualTo(3);
        verify(usageLimitRepository).saveAll(any());
    }

    @Test
    void question_guest_usesGuestRows() {
        service.checkAndConsumeQuestion(null, guest, "abc");

        verify(usageLimitRepository).insertGuestWindowIfAbsent(any(), eq(T0), eq(guest.getId()), eq("DAILY"));
        verify(usageLimitRepository).insertGuestWindowIfAbsent(any(), eq(T0), eq(guest.getId()), eq("WEEKLY"));
        verify(usageLimitRepository, never()).insertUserWindowIfAbsent(any(), any(), any(), any());
        assertThat(daily.getUsedTokens()).isEqualTo(1);
    }

    @Test
    void question_underLimit_isAllowedEvenIfQuestionPushesUsagePastLimit() {
        start(daily, 990, T0);
        start(weekly, 990, T0);

        // 30 tokens on top of 990 crosses the 1000 limit, but the check is on what was already used.
        service.checkAndConsumeQuestion(user, null, "abcabcabc ".repeat(10));

        assertThat(daily.getUsedTokens()).isEqualTo(1020);
    }

    @Test
    void question_dailyExhausted_throwsWithDailyWindowAndVietnamOffsetResetTime() {
        start(daily, 1000, T0);
        start(weekly, 1000, T0);
        weekly.setUsedTokens(10L); // weekly is fine

        assertThatThrownBy(() -> service.checkAndConsumeQuestion(user, null, "abc"))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_EXCEEDED);
                    assertThat(e.getErrors()).containsEntry("window", "DAILY");
                    // 03:00 UTC + 24h = 03:00 UTC next day = 10:00 +07:00
                    assertThat(e.getErrors().get("resetAt")).isEqualTo("2026-09-22T10:00+07:00");
                    assertThat(e.getErrors()).doesNotContainKeys("used", "max");
                });
        assertThat(daily.getUsedTokens()).isEqualTo(1000);
        verify(usageLimitRepository, never()).saveAll(any());
    }

    @Test
    void question_bothExhausted_reportsTheLaterReset() {
        start(daily, 1000, T0);
        start(weekly, 5000, T0);

        assertThatThrownBy(() -> service.checkAndConsumeQuestion(user, null, "abc"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrors()).containsEntry("window", "WEEKLY"));
    }

    @Test
    void question_expiredWindow_resetsAndLetsThrough() {
        start(daily, 1000, T0);
        start(weekly, 1000, T0);
        weekly.setUsedTokens(10L);
        clock.set(T0.plusHours(24)); // the daily window has just ended

        service.checkAndConsumeQuestion(user, null, "abc");

        assertThat(daily.getWindowStart()).isEqualTo(T0.plusHours(24));
        assertThat(daily.getUsedTokens()).isEqualTo(1); // reset to 0 then this question
        assertThat(weekly.getWindowStart()).isEqualTo(T0); // weekly keeps running
        assertThat(weekly.getUsedTokens()).isEqualTo(11);
    }

    @Test
    void question_windowsAreIndependent() {
        start(daily, 10, T0);
        start(weekly, 4990, T0);
        clock.set(T0.plusHours(25)); // daily over, weekly still running with room for 10 more

        service.checkAndConsumeQuestion(user, null, "abc");

        assertThat(daily.getUsedTokens()).isEqualTo(1);
        assertThat(weekly.getUsedTokens()).isEqualTo(4991);
    }

    @Test
    void question_unlimitedPlan_neverBlocks() {
        UsageLimitPlan unlimited = plan("Không giới hạn", null, null, false);
        user.getRole().setUsageLimitPlan(unlimited);
        start(daily, 999_999_999, T0);
        start(weekly, 999_999_999, T0);

        service.checkAndConsumeQuestion(user, null, "abc");

        assertThat(daily.getUsedTokens()).isEqualTo(1_000_000_000L);
    }

    @Test
    void question_userRoleWithPlan_usesRolePlanNotDefault() {
        user.getRole().setUsageLimitPlan(plan("Nhỏ", 5L, 50L, false));
        start(daily, 5, T0);
        start(weekly, 5, T0);

        assertThatThrownBy(() -> service.checkAndConsumeQuestion(user, null, "abc"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrors()).containsEntry("window", "DAILY"));
    }

    @Test
    void question_userRoleWithoutPlan_fallsBackToDefault() {
        start(daily, 999, T0);
        start(weekly, 999, T0);

        service.checkAndConsumeQuestion(user, null, "abc"); // default limit 1000: still allowed

        start(daily, 1000, T0);
        assertThatThrownBy(() -> service.checkAndConsumeQuestion(user, null, "abc"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void question_noDefaultPlan_failsClosed() {
        when(planRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkAndConsumeQuestion(null, guest, "abc"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USAGE_LIMIT_PLAN_MISSING));
        verify(usageLimitRepository, never()).saveAll(any());
    }

    @Test
    void question_needsExactlyOneIdentity() {
        assertThatThrownBy(() -> service.checkAndConsumeQuestion(null, null, "abc"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.checkAndConsumeQuestion(user, guest, "abc"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── consumeAnswer ────────────────────────────────────────────────────

    @Test
    void answer_addsTokensToRunningWindows() {
        start(daily, 10, T0);
        start(weekly, 10, T0);
        clock.set(T0.plusMinutes(5));

        service.consumeAnswer(user, null, "abcabcabc"); // 3 tokens

        assertThat(daily.getUsedTokens()).isEqualTo(13);
        assertThat(weekly.getUsedTokens()).isEqualTo(13);
        verify(usageLimitRepository, never()).insertUserWindowIfAbsent(any(), any(), any(), any());
    }

    @Test
    void answer_skipsWindowThatEndedWhileAnswering() {
        start(daily, 10, T0);
        start(weekly, 10, T0);
        clock.set(T0.plusHours(24).plusMinutes(1));

        service.consumeAnswer(user, null, "abcabcabc");

        assertThat(daily.getUsedTokens()).isEqualTo(10);
        assertThat(weekly.getUsedTokens()).isEqualTo(13);
    }

    @Test
    void answer_skipsWindowThatNeverStarted() {
        service.consumeAnswer(user, null, "abcabcabc");

        assertThat(daily.getUsedTokens()).isZero();
        assertThat(weekly.getUsedTokens()).isZero();
    }

    @Test
    void answer_blank_isNotCounted() {
        start(daily, 10, T0);

        service.consumeAnswer(user, null, "  ");

        assertThat(daily.getUsedTokens()).isEqualTo(10);
        verify(usageLimitRepository, never()).lockByUserId(any());
    }

    @Test
    void answer_disabled_doesNothing() {
        when(configResolver.getBoolean(eq("chat.usage_limit.enabled"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);

        service.consumeAnswer(user, null, "abc");

        verifyNoInteractions(usageLimitRepository);
    }

    // ── getUsage ─────────────────────────────────────────────────────────

    @Test
    void usage_disabled_isUnlimited() {
        when(configResolver.getBoolean(eq("chat.usage_limit.enabled"), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(false);

        UsageLimitResponse response = service.getUsage(user.getId(), null);

        assertThat(response.daily().status()).isEqualTo(UsageWindowStatus.UNLIMITED);
        assertThat(response.weekly().status()).isEqualTo(UsageWindowStatus.UNLIMITED);
    }

    @Test
    void usage_noRunningWindow_isIdleAtFullQuota() {
        UsageLimitResponse response = service.getUsage(user.getId(), null);

        assertThat(response.daily().status()).isEqualTo(UsageWindowStatus.IDLE);
        assertThat(response.daily().remainingPercent()).isEqualTo(100);
        assertThat(response.daily().resetAt()).isNull();
    }

    @Test
    void usage_expiredWindow_isIdle() {
        start(daily, 900, T0);
        clock.set(T0.plusHours(24));

        assertThat(service.getUsage(user.getId(), null).daily().status()).isEqualTo(UsageWindowStatus.IDLE);
    }

    @Test
    void usage_runningWindow_reportsRemainingPercentAndResetTime() {
        start(daily, 250, T0);   // 25% of 1000 used -> 75% left
        start(weekly, 4000, T0); // 80% of 5000 used -> 20% left

        UsageLimitResponse response = service.getUsage(user.getId(), null);

        assertThat(response.daily().status()).isEqualTo(UsageWindowStatus.ACTIVE);
        assertThat(response.daily().remainingPercent()).isEqualTo(75);
        assertThat(response.weekly().remainingPercent()).isEqualTo(20);
        assertThat(response.daily().resetAt().toString()).isEqualTo("2026-09-22T10:00+07:00");
        assertThat(response.weekly().resetAt().toString()).isEqualTo("2026-09-28T10:00+07:00");
    }

    @Test
    void usage_overTheLimit_isClampedToZeroPercent() {
        start(daily, 1500, T0);

        assertThat(service.getUsage(user.getId(), null).daily().remainingPercent()).isZero();
    }

    @Test
    void usage_unlimitedPlan_hasNoPercentOrReset() {
        user.getRole().setUsageLimitPlan(plan("Không giới hạn", null, null, false));
        start(daily, 500, T0);

        UsageLimitResponse response = service.getUsage(user.getId(), null);

        assertThat(response.daily().status()).isEqualTo(UsageWindowStatus.UNLIMITED);
        assertThat(response.daily().remainingPercent()).isNull();
        assertThat(response.daily().resetAt()).isNull();
    }

    @Test
    void usage_guestWithoutSession_reportsDefaultPlanWithNothingUsed() {
        UsageLimitResponse response = service.getUsage(null, null);

        assertThat(response.daily().status()).isEqualTo(UsageWindowStatus.IDLE);
        assertThat(response.weekly().remainingPercent()).isEqualTo(100);
        verify(usageLimitRepository, never()).findByUserId(any());
        verify(usageLimitRepository, never()).findByGuestSessionId(any());
        verify(usageLimitRepository, times(0)).saveAll(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static UsageLimitPlan plan(String name, Long dailyLimit, Long weeklyLimit, boolean isDefault) {
        return UsageLimitPlan.builder().id(UUID.randomUUID()).name(name)
                .dailyTokenLimit(dailyLimit).weeklyTokenLimit(weeklyLimit).isDefault(isDefault).build();
    }

    private static UsageLimit row(UsageLimitWindow window) {
        return UsageLimit.builder().id(UUID.randomUUID()).windowType(window).build();
    }

    private static void start(UsageLimit row, long used, LocalDateTime windowStart) {
        row.setUsedTokens(used);
        row.setWindowStart(windowStart);
    }

    /** UTC clock the test can move. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(LocalDateTime utc) {
            this.now = utc.toInstant(ZoneOffset.UTC);
        }

        void set(LocalDateTime utc) {
            this.now = utc.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
