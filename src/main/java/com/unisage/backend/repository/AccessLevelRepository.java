package com.unisage.backend.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.unisage.backend.entity.AccessLevel;

@Repository
public interface AccessLevelRepository extends JpaRepository<AccessLevel, UUID> {

    boolean existsByLevel(Integer level);

    Optional<AccessLevel> findByLevel(Integer level);
}
