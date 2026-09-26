package com.unisage.backend.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.ChatModel;

public interface ChatModelRepository extends JpaRepository<ChatModel, UUID> {

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    Page<ChatModel> findAll(Pageable pageable);

    /**
     * Atomic counter/last-error update for a health report — comparing {@code revision} and
     * writing in the same statement (rather than read-then-write) is what makes a stale report a
     * no-op instead of a race (plan.md "Internal API contract" endpoint #3, R2.6).
     *
     * @return 1 if the row's current revision matched and was updated, 0 if it was stale (caller
     *         treats 0 as "ignore the whole report").
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatModel c SET c.errorCount = c.errorCount + 1, c.lastErrorAt = :occurredAt, "
            + "c.lastErrorCode = :errorCode, c.lastErrorMessage = :message "
            + "WHERE c.id = :id AND c.revision = :revision")
    int recordHealthError(
            @Param("id") UUID id,
            @Param("revision") Integer revision,
            @Param("occurredAt") LocalDateTime occurredAt,
            @Param("errorCode") String errorCode,
            @Param("message") String message);
}
