package com.unisage.backend.service.chatmodel;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Staged credential rotation from plan.md "Credential rotation". Skeleton — bodies filled in
 * once the update-key / promote service exists.
 */
@Disabled("skeleton — enable once staged rotation service exists")
class ChatModelRotationTest {

    @Test
    void activeRow_editApiKey_snapshotStillServesOldKeyUntilVerified() {
    }

    @Test
    void activeRow_verifyFail_oldKeyStillRuns_rowUnchanged() {
    }

    @Test
    void update_apiKeyFieldAbsent_keepsOldKey() {
    }

    @Test
    void update_apiKeyNull_keepsOldKey() {
    }

    @Test
    void update_apiKeyEmptyOrBlank_400() {
    }

    @Test
    void update_apiKeyNewValue_becomesCandidate_notWrittenYet() {
    }

    @Test
    void update_apiBaseUrlHostChanged_withoutNewApiKey_400() {
    }

    @Test
    void update_apiBaseUrlHostChanged_withNewApiKey_ok() {
    }

    @Test
    void clearApiKey_onlyValidForSelfHosted() {
    }

    @Test
    void create_startsAtRevisionZero_candidateGenerationOne() {
    }

    @Test
    void verifyOk_copiesCandidateIntoRow_revisionIncrements_verifiedAtSet() {
    }

    @Test
    void editWhileCandidatePending_bumpsCandidateGeneration_oldJobSuperseded() {
    }

    @Test
    void priorityAndMaxRpmEdits_applyImmediately_noVerifyNeeded() {
    }
}
