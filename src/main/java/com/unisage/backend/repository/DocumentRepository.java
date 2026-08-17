package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.Document;
import com.unisage.backend.entity.enums.DocStatus;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    @Query("SELECT d FROM Document d WHERE d.deletedAt IS NULL")
    Page<Document> findAllActive(Pageable pageable);

    List<Document> findByDocPackageIdAndDeletedAtIsNull(UUID docPackageId);

    List<Document> findByStatusAndDeletedAtIsNull(DocStatus status);
}
