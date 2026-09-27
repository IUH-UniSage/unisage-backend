package com.unisage.backend.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.unisage.backend.entity.EmbeddingIndexIdentity;

/**
 * Deliberately extends the bare marker {@link Repository}, not {@code JpaRepository} or
 * {@code CrudRepository} — this interface has no {@code save}/{@code delete} method at all, one of
 * the 3 layers guarding {@code embedding_index_identity} against mutation (see
 * {@link EmbeddingIndexIdentity}). The only way to create a row is
 * {@link EmbeddingIndexIdentityWriter#insertIfAbsent}.
 */
public interface EmbeddingIndexIdentityRepository extends Repository<EmbeddingIndexIdentity, String> {

    Optional<EmbeddingIndexIdentity> findByCollectionName(String collectionName);
}
