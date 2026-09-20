package com.unisage.backend.service.systemconfig;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.entity.SystemConfig;
import com.unisage.backend.repository.SystemConfigRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Typed, fail-safe reads of {@code system_configs} for services that enforce the business rule a
 * row controls (usage limits, guest session TTL/cleanup, chat history window, upload validation)
 * — as opposed to {@link SystemConfigService}, which is the admin CRUD API. Every getter falls
 * back to the caller-supplied default instead of throwing, so a missing row (fresh DB before
 * Flyway seeds it), a row an admin edited into something unparsable, or an inactive row all
 * degrade to the old hardcoded behavior instead of breaking the feature reading it. No caching —
 * one lookup by unique indexed {@code config_key} per call is cheap enough at this call volume;
 * add a cache only if profiling ever says otherwise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemConfigResolver {

    private final SystemConfigRepository systemConfigRepository;
    private final ObjectMapper objectMapper;

    public int getInt(String configKey, int fallback) {
        Optional<String> value = findValue(configKey);
        if (value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.get().trim());
        } catch (NumberFormatException e) {
            log.warn("system_configs '{}' = '{}' is not a valid int, using fallback {}",
                    configKey, value.get(), fallback);
            return fallback;
        }
    }

    public boolean getBoolean(String configKey, boolean fallback) {
        Optional<String> value = findValue(configKey);
        if (value.isEmpty()) return fallback;
        String trimmed = value.get().trim();
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;
        log.warn("system_configs '{}' = '{}' is not a valid boolean, using fallback {}",
                configKey, trimmed, fallback);
        return fallback;
    }

    public List<String> getStringList(String configKey, List<String> fallback) {
        Optional<String> value = findValue(configKey);
        if (value.isEmpty()) return fallback;
        try {
            String[] parsed = objectMapper.readValue(value.get(), String[].class);
            return List.of(parsed);
        } catch (Exception e) {
            log.warn("system_configs '{}' is not a valid JSON string array, using fallback",
                    configKey);
            return fallback;
        }
    }

    private Optional<String> findValue(String configKey) {
        return systemConfigRepository.findByConfigKey(configKey)
                .filter(config -> Boolean.TRUE.equals(config.getIsActive()))
                .map(SystemConfig::getValue);
    }
}
