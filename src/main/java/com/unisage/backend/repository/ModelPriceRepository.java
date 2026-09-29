package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.ModelPrice;

public interface ModelPriceRepository extends JpaRepository<ModelPrice, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    List<ModelPrice> findAllByOrderByProviderAscModelNameAsc();
}
