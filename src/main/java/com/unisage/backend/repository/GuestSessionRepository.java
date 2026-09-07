package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.GuestSession;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {

    Optional<GuestSession> findByTokenHashAndExpiresAtAfter(String tokenHash, LocalDateTime now);
}
