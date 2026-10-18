package com.unisage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.EmbeddingReindexRun;
import com.unisage.backend.entity.enums.EmbeddingReindexStatus;

public interface EmbeddingReindexRunRepository extends JpaRepository<EmbeddingReindexRun, UUID> {

    /**
     * Claimable run ids — {@code QUEUED}, or {@code RUNNING} with an expired lease (the worker
     * died) — locked {@code FOR UPDATE SKIP LOCKED} so two concurrent claims never take the same
     * run. Native because JPQL has no {@code SKIP LOCKED}; the claim write happens in the caller's
     * transaction.
     */
    @Query(value = """
            SELECT id FROM embedding_reindex_runs
            WHERE status = 'QUEUED'
               OR (status = 'RUNNING' AND lease_until < now())
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EmbeddingReindexRun r WHERE r.id = :id")
    Optional<EmbeddingReindexRun> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"targetChatModel"})
    Optional<EmbeddingReindexRun> findFirstByBaseCollectionAndStatusInOrderByCreatedAtDesc(
            String baseCollection, Collection<EmbeddingReindexStatus> statuses);

    @EntityGraph(attributePaths = {"targetChatModel"})
    Optional<EmbeddingReindexRun> findFirstByBaseCollectionOrderByCreatedAtDesc(String baseCollection);

    @EntityGraph(attributePaths = {"targetChatModel"})
    Optional<EmbeddingReindexRun> findWithModelById(UUID id);
}
