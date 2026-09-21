package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.UsageLimit;

import jakarta.persistence.LockModeType;

public interface UsageLimitRepository extends JpaRepository<UsageLimit, UUID> {

    /**
     * Creates the counter row of a (user, window) pair if it is missing. Done in SQL with ON CONFLICT
     * so two concurrent first requests cannot both insert.
     */
    @Modifying
    @Query(value = """
            INSERT INTO usage_limits (id, created_at, is_active, user_id, window_type, used_tokens)
            VALUES (:id, :now, true, :userId, :windowType, 0)
            ON CONFLICT (user_id, window_type) DO NOTHING
            """, nativeQuery = true)
    void insertUserWindowIfAbsent(@Param("id") UUID id, @Param("now") LocalDateTime now,
            @Param("userId") UUID userId, @Param("windowType") String windowType);

    @Modifying
    @Query(value = """
            INSERT INTO usage_limits (id, created_at, is_active, guest_session_id, window_type, used_tokens)
            VALUES (:id, :now, true, :guestSessionId, :windowType, 0)
            ON CONFLICT (guest_session_id, window_type) DO NOTHING
            """, nativeQuery = true)
    void insertGuestWindowIfAbsent(@Param("id") UUID id, @Param("now") LocalDateTime now,
            @Param("guestSessionId") UUID guestSessionId, @Param("windowType") String windowType);

    /** Row-locks both window rows of a user. Ordered by window type so every caller locks in the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UsageLimit u WHERE u.user.id = :userId ORDER BY u.windowType")
    List<UsageLimit> lockByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UsageLimit u WHERE u.guestSession.id = :guestSessionId ORDER BY u.windowType")
    List<UsageLimit> lockByGuestSessionId(@Param("guestSessionId") UUID guestSessionId);

    @Query("SELECT u FROM UsageLimit u WHERE u.user.id = :userId")
    List<UsageLimit> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT u FROM UsageLimit u WHERE u.guestSession.id = :guestSessionId")
    List<UsageLimit> findByGuestSessionId(@Param("guestSessionId") UUID guestSessionId);

    /** Bulk delete for the guest-session cleanup job. */
    @Modifying
    @Query("DELETE FROM UsageLimit u WHERE u.guestSession.id IN :guestSessionIds")
    void deleteByGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
