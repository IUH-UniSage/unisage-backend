package com.unisage.backend.service.modelregistry;

import java.util.UUID;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.dto.response.internal.InternalModelRegistrySnapshotResponse;

/**
 * Business logic behind {@link com.unisage.backend.controller.internal.InternalModelRegistryController}
 * that doesn't already have its own dedicated service (version/embedding-index-identity keep theirs).
 */
public interface ModelRegistryInternalService {

    /**
     * @throws com.unisage.backend.exception.AppException(CHAT_MODEL_NOT_FOUND) if no credential
     *         with this id exists at all (distinct from a stale-revision report, which is a normal
     *         {@code applied: false}, not an error).
     */
    CredentialHealthReportResponse reportHealth(UUID chatModelId, CredentialHealthReportRequest request);

    /**
     * plan.md "Internal API contract" endpoint #1 — every ACTIVE credential grouped by purpose, plus
     * the registry version and the embedding index identity, read together in one read-only
     * {@code REPEATABLE_READ} transaction so version and data can never disagree.
     */
    InternalModelRegistrySnapshotResponse getSnapshot();
}
