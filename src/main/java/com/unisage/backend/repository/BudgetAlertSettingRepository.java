package com.unisage.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.unisage.backend.entity.BudgetAlertSetting;

public interface BudgetAlertSettingRepository extends JpaRepository<BudgetAlertSetting, Short> {
}
