package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.BackupType;

import jakarta.validation.constraints.NotNull;

/** "Sao lưu ngay": runs one backup type now, outside the schedule. */
public record ManualBackupRequest(@NotNull BackupType type) {
}
