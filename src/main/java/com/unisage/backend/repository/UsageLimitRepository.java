package com.unisage.backend.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.UsageLimit;

public interface UsageLimitRepository extends JpaRepository<UsageLimit, UUID> {

    Optional<UsageLimit> findByUserIdAndLimitTypeAndScopeAndScopeDate(
            UUID userId, String limitType, String scope, LocalDate scopeDate);

    Optional<UsageLimit> findByIpAddressAndLimitTypeAndScopeAndScopeDate(
            String ipAddress, String limitType, String scope, LocalDate scopeDate);
}
