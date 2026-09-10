package com.unisage.backend.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.enums.UsageLimitScope;
import com.unisage.backend.entity.enums.UsageLimitType;

public interface UsageLimitRepository extends JpaRepository<UsageLimit, UUID> {

    Optional<UsageLimit> findByUserIdAndLimitTypeAndScopeAndScopeDate(
            UUID userId, UsageLimitType limitType, UsageLimitScope scope, LocalDate scopeDate);

    Optional<UsageLimit> findByGuestSessionIdAndLimitTypeAndScopeAndScopeDate(
            UUID guestSessionId, UsageLimitType limitType, UsageLimitScope scope, LocalDate scopeDate);

    /** Bulk delete for the guest-session cleanup job. */
    @Modifying
    @Query("DELETE FROM UsageLimit u WHERE u.guestSession.id IN :guestSessionIds")
    void deleteByGuestSessionIdIn(@Param("guestSessionIds") Collection<UUID> guestSessionIds);
}
