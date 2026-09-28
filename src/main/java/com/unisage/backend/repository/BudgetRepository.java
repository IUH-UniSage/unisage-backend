package com.unisage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.unisage.backend.entity.Budget;
import com.unisage.backend.entity.enums.BudgetScope;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    List<Budget> findByIsEnabledTrueAndIsActiveTrue();

    @Query("SELECT DISTINCT b.scopeProvider FROM Budget b "
            + "WHERE b.scope = :scope AND b.isEnabled = true AND b.isActive = true")
    List<String> findDistinctEnabledScopeProviders(@Param("scope") BudgetScope scope);
}
