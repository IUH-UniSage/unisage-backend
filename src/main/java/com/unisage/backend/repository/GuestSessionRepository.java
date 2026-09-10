package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.GuestSession;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {

    Optional<GuestSession> findByTokenHashAndExpiresAtAfter(String tokenHash, LocalDateTime now);

    /** One page of expired session ids, oldest first — used by the batched cleanup job. */
    @Query("SELECT gs.id FROM GuestSession gs WHERE gs.expiresAt < :now ORDER BY gs.expiresAt ASC")
    List<UUID> findExpiredIds(@Param("now") LocalDateTime now, Pageable pageable);
}
