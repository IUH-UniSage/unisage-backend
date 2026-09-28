package com.unisage.backend.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.Budget;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {
}
