package com.unisage.backend.service.backup;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code maintenance.backup.*} rows of {@code system_configs} (seeded by V35), read live by
 * {@link BackupSettingsReader} on every {@code BackupJob} tick. {@link #isValid} is the single
 * place that knows what each row accepts: {@code SystemConfigServiceImpl} rejects an admin edit
 * that fails it, and the reader falls back to the seeded default for a row that somehow does.
 */
public record BackupSettings(
        boolean enabled,
        BackupFrequency fullFrequency,
        LocalTime fullTime,
        BackupFrequency differentialFrequency,
        LocalTime differentialTime,
        int walIntervalMinutes,
        int fullRetentionCount,
        BackupFrequency qdrantFrequency,
        LocalTime qdrantTime,
        int qdrantRetentionCount) {

    public static final String ENABLED_KEY = "maintenance.backup.enabled";
    public static final String FULL_FREQUENCY_KEY = "maintenance.backup.full.frequency";
    public static final String FULL_TIME_KEY = "maintenance.backup.full.time";
    public static final String DIFFERENTIAL_FREQUENCY_KEY = "maintenance.backup.differential.frequency";
    public static final String DIFFERENTIAL_TIME_KEY = "maintenance.backup.differential.time";
    public static final String WAL_INTERVAL_KEY = "maintenance.backup.wal.interval_minutes";
    public static final String FULL_RETENTION_KEY = "maintenance.backup.retention.full_count";
    public static final String QDRANT_FREQUENCY_KEY = "maintenance.backup.qdrant.frequency";
    public static final String QDRANT_TIME_KEY = "maintenance.backup.qdrant.time";
    public static final String QDRANT_RETENTION_KEY = "maintenance.backup.qdrant.retention_count";

    static final int MAX_WAL_INTERVAL_MINUTES = 24 * 60;
    static final int MAX_RETENTION_COUNT = 365;

    private static final Set<String> TIME_KEYS = Set.of(FULL_TIME_KEY, DIFFERENTIAL_TIME_KEY, QDRANT_TIME_KEY);
    private static final Set<String> RETENTION_KEYS = Set.of(FULL_RETENTION_KEY, QDRANT_RETENTION_KEY);
    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,9}");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    /** True for every key outside this group, so callers can run it on any config edit. */
    public static boolean isValid(String configKey, String value) {
        if (configKey == null || !configKey.startsWith("maintenance.backup.") || value == null) {
            return true;
        }
        String trimmed = value.trim();
        if (FULL_FREQUENCY_KEY.equals(configKey)) {
            // Every differential and every WAL segment hangs off a full backup.
            return BackupFrequency.parse(trimmed).filter(f -> f != BackupFrequency.OFF).isPresent();
        }
        if (DIFFERENTIAL_FREQUENCY_KEY.equals(configKey) || QDRANT_FREQUENCY_KEY.equals(configKey)) {
            return BackupFrequency.parse(trimmed).isPresent();
        }
        if (TIME_KEYS.contains(configKey)) {
            return parseTime(trimmed).isPresent();
        }
        if (WAL_INTERVAL_KEY.equals(configKey)) {
            return parseWholeNumber(trimmed).filter(n -> n <= MAX_WAL_INTERVAL_MINUTES).isPresent();
        }
        if (RETENTION_KEYS.contains(configKey)) {
            return parseWholeNumber(trimmed).filter(n -> n >= 1 && n <= MAX_RETENTION_COUNT).isPresent();
        }
        return true;
    }

    static Optional<LocalTime> parseTime(String value) {
        try {
            return Optional.of(LocalTime.parse(value.trim(), TIME_FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The Settings page sends NUMBER rows as typed, so "15.0" or "-1" must not get through. */
    static Optional<Integer> parseWholeNumber(String value) {
        String trimmed = value.trim();
        return WHOLE_NUMBER.matcher(trimmed).matches() ? Optional.of(Integer.parseInt(trimmed)) : Optional.empty();
    }
}
