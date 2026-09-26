package com.unisage.backend.service.modelregistryversion;

/**
 * Bumps the singleton registry version — DB is the source of truth, Redis (see
 * {@code ModelRegistryEventPublisher}) is only a best-effort wake-up signal.
 */
public interface ModelRegistryVersionService {

    /**
     * Must run inside an existing transaction — the version bump has to commit or roll back with
     * whatever registry change caused it, never on its own.
     */
    long bump();

    long currentVersion();
}
