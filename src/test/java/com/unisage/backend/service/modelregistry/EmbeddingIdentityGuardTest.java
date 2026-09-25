package com.unisage.backend.service.modelregistry;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Embedding identity guard from plan.md "Embedding identity guard". Skeleton — bodies filled in
 * once the identity comparison exists on verify/activate/swap (Task 1/3/13).
 */
@Disabled("skeleton — enable once the identity comparison exists")
class EmbeddingIdentityGuardTest {

    @Test
    void activeEmbedding_candidateChangesModelName_verifyOk_becomesReindexRequired_rowUnchanged() {
    }

    @Test
    void activeEmbedding_candidateChangesProvider_becomesReindexRequired() {
    }

    @Test
    void activeEmbedding_candidateChangesApiBaseUrl_becomesReindexRequired() {
    }

    @Test
    void activeEmbedding_candidateChangesModelSourceRef_becomesReindexRequired() {
    }

    @Test
    void activeEmbedding_candidateDimensionDiffers_becomesReindexRequired() {
    }

    @Test
    void activeEmbedding_onlyKeyChanges_fingerprintMatches_promotes() {
    }

    @Test
    void activeEmbedding_onlyKeyChanges_fingerprintDiffers_becomesReindexRequired() {
    }

    @Test
    void inactiveEmbedding_modelChange_promotesNormally_notInSnapshot() {
    }

    @Test
    void inactiveEmbedding_afterModelChange_activate_rejected409ReindexRequired() {
    }

    @Test
    void swapActivate_differentIdentityWhileOtherActive_rejected409_originalStaysActive() {
    }

    @Test
    void swapActivate_sameIdentity_succeeds() {
    }

    @Test
    void disabledRow_reactivateAfterKeyFix_sameIdentity_allowed() {
    }

    @Test
    void disabledRow_reactivateWithDifferentIdentity_rejected409() {
    }

    @Test
    void putIdentity_secondCallSameCollection_409_identityUnchangedRegardlessOfBody() {
    }
}
