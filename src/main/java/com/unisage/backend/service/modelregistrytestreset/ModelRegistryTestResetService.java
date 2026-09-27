package com.unisage.backend.service.modelregistrytestreset;

/**
 * Backs {@code POST /internal/test/registry/reset} (todo.md Task 0.5) — only ever registered
 * under profile {@code integration}. See {@link ModelRegistryTestResetServiceImpl} for the exact
 * transaction contract.
 */
public interface ModelRegistryTestResetService {

    /** @throws com.unisage.backend.exception.AppException(REGISTRY_RESET_JOB_RUNNING) as a 409. */
    void reset();
}
