package com.unisage.backend.service.conversation;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-identity message rate limit: at most {@code chat.rate_limit.messages_per_minute} (system
 * setting, default 3, 0 = off) questions in any 60-second window, counted per user or per guest
 * session. Independent of the token quota ({@link com.unisage.backend.service.usagelimit.UsageLimitService}):
 * that one bounds how much is asked per day/week, this one how fast.
 *
 * <p>The counter is a sliding log in Redis (sorted set of send times, trimmed to the window,
 * one Lua script so check + record is atomic across backend instances) - nothing is written to
 * the database. Redis unavailable means the request goes through with a warning: a slow Redis
 * must not take the chat down, the token quota still applies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageRateLimiter {
    static final String MESSAGES_PER_MINUTE_KEY = "chat.rate_limit.messages_per_minute";
    static final int DEFAULT_MESSAGES_PER_MINUTE = 3;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final String KEY_PREFIX = "chat:rate:msg:";

    /**
     * KEYS[1] = identity key; ARGV = now (ms), window (ms), limit, member. Drops entries older
     * than the window, then either records this send and answers {1, 0}, or refuses and answers
     * {0, ms until the oldest entry leaves the window}.
     */
    private static final String LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count < limit then
              redis.call('ZADD', key, now, ARGV[4])
              redis.call('PEXPIRE', key, window)
              return {1, 0}
            end
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            redis.call('PEXPIRE', key, window)
            return {0, tonumber(oldest[2]) + window - now}
            """;
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redisTemplate;
    private final SystemConfigResolver configResolver;
    private final Clock clock;

    /**
     * Records one question for the identity, or throws {@link ErrorCode#MESSAGE_RATE_LIMITED}
     * with {@code limit}, {@code windowSeconds} and {@code retryAfterSeconds} details.
     */
    public void checkAndRecord(User user, GuestSession guestSession) {
        int limit = configResolver.getInt(MESSAGES_PER_MINUTE_KEY, DEFAULT_MESSAGES_PER_MINUTE);
        String key = keyOf(user, guestSession);
        if (limit <= 0 || key == null) {
            return;
        }
        long now = clock.millis();
        List<?> result;
        try {
            result = redisTemplate.execute(SCRIPT, List.of(key),
                    String.valueOf(now), String.valueOf(WINDOW.toMillis()), String.valueOf(limit),
                    now + ":" + UUID.randomUUID());
        } catch (DataAccessException e) {
            log.warn("Message rate limit skipped for {}: Redis unavailable ({})", key, e.getMessage());
            return;
        }
        if (result == null || result.size() < 2 || toLong(result.get(0)) == 1L) {
            return;
        }
        long retryAfterSeconds = Math.max(1L, (toLong(result.get(1)) + 999L) / 1000L);
        throw new AppException(ErrorCode.MESSAGE_RATE_LIMITED, Map.of(
                "limit", String.valueOf(limit),
                "windowSeconds", String.valueOf(WINDOW.toSeconds()),
                "retryAfterSeconds", String.valueOf(retryAfterSeconds)));
    }

    static String keyOf(User user, GuestSession guestSession) {
        if (user != null && user.getId() != null) {
            return KEY_PREFIX + "user:" + user.getId();
        }
        if (guestSession != null && guestSession.getId() != null) {
            return KEY_PREFIX + "guest:" + guestSession.getId();
        }
        return null;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
