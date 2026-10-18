package com.unisage.backend.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which Qdrant collection is live for a base collection ({@code model-registry.embedding-collection-name}).
 * A missing row means the base collection itself is live. Moved only by a re-index cutover.
 */
@Entity
@Table(name = "embedding_index_pointer")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmbeddingIndexPointer {

    @Id
    @Column(name = "base_collection", updatable = false, nullable = false)
    private String baseCollection;

    @Column(name = "active_collection", nullable = false)
    private String activeCollection;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
