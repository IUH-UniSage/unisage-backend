package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    boolean existsByName(String name);

    @Override
    @EntityGraph(attributePaths = {"createdBy", "updatedBy"})
    List<Category> findAll();
}
