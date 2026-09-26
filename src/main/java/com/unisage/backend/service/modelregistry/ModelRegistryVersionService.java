package com.unisage.backend.service.modelregistry;

import com.unisage.backend.event.ModelRegistryChangedEvent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Bumps the singleton registry version — DB is the source of truth, Redis (see
 * {@link ModelRegistryEventPublisher}) is only a best-effort wake-up signal.
 */
@Service
@RequiredArgsConstructor
public class ModelRegistryVersionService {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Must run inside an existing transaction — the version bump has to commit or roll back with
     * whatever registry change caused it, never on its own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long bump() {
        Long version = jdbcTemplate.queryForObject(
                "UPDATE model_registry_version SET version = version + 1, updated_at = now() "
                        + "WHERE id = 1 RETURNING version",
                Long.class);
        eventPublisher.publishEvent(new ModelRegistryChangedEvent(version));
        return version;
    }

    public long currentVersion() {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM model_registry_version WHERE id = 1", Long.class);
        return version != null ? version : 0L;
    }
}
