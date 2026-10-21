package com.unisage.backend.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

class MessageRateLimiterTest {
    private static final Instant T0 = Instant.parse("2026-10-21T03:00:00Z");

    private StringRedisTemplate redisTemplate;
    private SystemConfigResolver configResolver;
    private MessageRateLimiter limiter;
    private final User user = User.builder().id(UUID.randomUUID()).build();
    private final GuestSession guest = GuestSession.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        configResolver = mock(SystemConfigResolver.class);
        when(configResolver.getInt(eq(MessageRateLimiter.MESSAGES_PER_MINUTE_KEY), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        limiter = new MessageRateLimiter(redisTemplate, configResolver, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private void scriptAnswers(List<Long> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(result);
    }

    @Test
    void underTheLimit_recordsAndReturns() {
        scriptAnswers(List.of(1L, 0L));

        limiter.checkAndRecord(user, null);

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(MessageRateLimiter.KEY_PREFIX + "user:" + user.getId())),
                eq(String.valueOf(T0.toEpochMilli())), eq("60000"), eq("3"), any());
    }

    @Test
    void overTheLimit_throws429WithRetryAfterRoundedUp() {
        scriptAnswers(List.of(0L, 12_001L));

        assertThatThrownBy(() -> limiter.checkAndRecord(null, guest))
                .isInstanceOf(AppException.class)
                .satisfies(error -> {
                    AppException app = (AppException) error;
                    assertThat(app.getErrorCode()).isEqualTo(ErrorCode.MESSAGE_RATE_LIMITED);
                    assertThat(app.getErrors()).containsEntry("limit", "3")
                            .containsEntry("windowSeconds", "60")
                            .containsEntry("retryAfterSeconds", "13");
                });
        verify(redisTemplate).execute(any(RedisScript.class),
                eq(List.of(MessageRateLimiter.KEY_PREFIX + "guest:" + guest.getId())),
                any(), any(), any(), any());
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        scriptAnswers(List.of(0L, 0L));

        assertThatThrownBy(() -> limiter.checkAndRecord(user, null))
                .isInstanceOf(AppException.class)
                .satisfies(error -> assertThat(((AppException) error).getErrors())
                        .containsEntry("retryAfterSeconds", "1"));
    }

    @Test
    void settingZero_turnsTheLimitOff() {
        when(configResolver.getInt(eq(MessageRateLimiter.MESSAGES_PER_MINUTE_KEY), anyInt())).thenReturn(0);

        limiter.checkAndRecord(user, null);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void settingIsPassedToTheScriptAsTheLimit() {
        when(configResolver.getInt(eq(MessageRateLimiter.MESSAGES_PER_MINUTE_KEY), anyInt())).thenReturn(10);
        scriptAnswers(List.of(1L, 0L));

        limiter.checkAndRecord(user, null);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), eq("10"), any());
    }

    @Test
    void noIdentity_isNotCounted() {
        limiter.checkAndRecord(null, null);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDown_letsTheMessageThrough() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        limiter.checkAndRecord(user, null);
    }
}
