package com.unisage.backend.service.backup;

import java.time.LocalTime;

import org.springframework.stereotype.Component;

import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads {@link BackupSettings} fail-safe: a missing row, or one that fails
 * {@link BackupSettings#isValid}, falls back to its V35 default instead of stopping every backup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackupSettingsReader {

    private final SystemConfigResolver configResolver;

    public BackupSettings read() {
        return new BackupSettings(
                configResolver.getBoolean(BackupSettings.ENABLED_KEY, false),
                frequency(BackupSettings.FULL_FREQUENCY_KEY, BackupFrequency.WEEKLY),
                time(BackupSettings.FULL_TIME_KEY, LocalTime.of(2, 0)),
                frequency(BackupSettings.DIFFERENTIAL_FREQUENCY_KEY, BackupFrequency.DAILY),
                time(BackupSettings.DIFFERENTIAL_TIME_KEY, LocalTime.of(3, 0)),
                number(BackupSettings.WAL_INTERVAL_KEY, 15),
                number(BackupSettings.FULL_RETENTION_KEY, 4),
                frequency(BackupSettings.QDRANT_FREQUENCY_KEY, BackupFrequency.DAILY),
                time(BackupSettings.QDRANT_TIME_KEY, LocalTime.of(3, 30)),
                number(BackupSettings.QDRANT_RETENTION_KEY, 7));
    }

    private BackupFrequency frequency(String key, BackupFrequency fallback) {
        String value = validOrNull(key);
        return value == null ? fallback : BackupFrequency.parse(value).orElse(fallback);
    }

    private LocalTime time(String key, LocalTime fallback) {
        String value = validOrNull(key);
        return value == null ? fallback : BackupSettings.parseTime(value).orElse(fallback);
    }

    private int number(String key, int fallback) {
        String value = validOrNull(key);
        return value == null ? fallback : BackupSettings.parseWholeNumber(value).orElse(fallback);
    }

    private String validOrNull(String key) {
        String value = configResolver.getString(key, null);
        if (value != null && !BackupSettings.isValid(key, value)) {
            log.warn("system_configs '{}' = '{}' is not a valid backup setting, using the default", key, value);
            return null;
        }
        return value;
    }
}
