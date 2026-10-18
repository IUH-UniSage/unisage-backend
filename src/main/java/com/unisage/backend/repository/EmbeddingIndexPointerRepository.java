package com.unisage.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.EmbeddingIndexPointer;

public interface EmbeddingIndexPointerRepository extends JpaRepository<EmbeddingIndexPointer, String> {

    /** Points {@code baseCollection} at {@code activeCollection}, creating the row on the first cutover. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO embedding_index_pointer (base_collection, active_collection, updated_at)
            VALUES (:baseCollection, :activeCollection, now())
            ON CONFLICT (base_collection)
            DO UPDATE SET active_collection = EXCLUDED.active_collection, updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("baseCollection") String baseCollection, @Param("activeCollection") String activeCollection);
}
