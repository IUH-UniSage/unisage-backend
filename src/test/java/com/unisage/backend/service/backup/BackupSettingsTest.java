package com.unisage.backend.service.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupSettingsTest {

    @Test
    void fullFrequency_cannotBeOff() {
        assertThat(BackupSettings.isValid(BackupSettings.FULL_FREQUENCY_KEY, "MONTHLY")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_FREQUENCY_KEY, "OFF")).isFalse();
    }

    @Test
    void differentialAndQdrantFrequency_acceptOff() {
        assertThat(BackupSettings.isValid(BackupSettings.DIFFERENTIAL_FREQUENCY_KEY, "OFF")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.QDRANT_FREQUENCY_KEY, "yearly")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.QDRANT_FREQUENCY_KEY, "HOURLY")).isFalse();
    }

    @Test
    void time_mustBe24HourHoursAndMinutes() {
        assertThat(BackupSettings.isValid(BackupSettings.FULL_TIME_KEY, "00:00")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_TIME_KEY, "23:59")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_TIME_KEY, "24:00")).isFalse();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_TIME_KEY, "2:00")).isFalse();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_TIME_KEY, "02:00:00")).isFalse();
    }

    @Test
    void walInterval_allowsZeroUpToOneDay() {
        assertThat(BackupSettings.isValid(BackupSettings.WAL_INTERVAL_KEY, "0")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.WAL_INTERVAL_KEY, "1440")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.WAL_INTERVAL_KEY, "1441")).isFalse();
        assertThat(BackupSettings.isValid(BackupSettings.WAL_INTERVAL_KEY, "-1")).isFalse();
        assertThat(BackupSettings.isValid(BackupSettings.WAL_INTERVAL_KEY, "15.5")).isFalse();
    }

    @Test
    void retention_mustKeepAtLeastOne() {
        assertThat(BackupSettings.isValid(BackupSettings.FULL_RETENTION_KEY, "1")).isTrue();
        assertThat(BackupSettings.isValid(BackupSettings.FULL_RETENTION_KEY, "0")).isFalse();
        assertThat(BackupSettings.isValid(BackupSettings.QDRANT_RETENTION_KEY, "366")).isFalse();
    }

    @Test
    void otherKeys_areNotChecked() {
        assertThat(BackupSettings.isValid("chat.max_history_messages", "anything")).isTrue();
    }
}
