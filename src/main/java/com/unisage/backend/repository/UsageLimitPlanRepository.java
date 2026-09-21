package com.unisage.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.unisage.backend.entity.UsageLimitPlan;

public interface UsageLimitPlanRepository extends JpaRepository<UsageLimitPlan, UUID> {

    Optional<UsageLimitPlan> findByName(String name);

    Optional<UsageLimitPlan> findFirstByIsDefaultTrue();

    long countByIsDefaultTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);

    /** Step one of moving the default flag: clears it everywhere so the single-default index is never violated. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE UsageLimitPlan p SET p.isDefault = false WHERE p.isDefault = true")
    void clearDefault();

    List<UsageLimitPlan> findAllByOrderByNameAsc();
}
