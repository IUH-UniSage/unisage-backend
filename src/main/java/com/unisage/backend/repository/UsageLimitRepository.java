package com.unisage.backend.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.UsageLimit;
import com.unisage.backend.entity.enums.UsageLimitScope;
import com.unisage.backend.entity.enums.UsageLimitType;

public interface UsageLimitRepository extends JpaRepository<UsageLimit, UUID> {

    Optional<UsageLimit> findByUserIdAndLimitTypeAndScopeAndScopeDate(
            UUID userId, UsageLimitType limitType, UsageLimitScope scope, LocalDate scopeDate);

    Optional<UsageLimit> findByIpAddressAndLimitTypeAndScopeAndScopeDate(
            String ipAddress, UsageLimitType limitType, UsageLimitScope scope, LocalDate scopeDate);
}
