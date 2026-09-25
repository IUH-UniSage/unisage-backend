package com.unisage.backend.service.chatmodel;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * State-transition matrix from plan.md "State machine". Skeleton — bodies filled in once the
 * transition service (compare-and-set on status) exists. Names double as the scenario list.
 */
@Disabled("skeleton — enable once ChatModel state-transition service exists")
class ChatModelStateTransitionTest {

    @Test
    void create_startsAtPending_revisionZero() {
    }

    @Test
    void pending_verifyOk_chatOrExtraction_becomesActive() {
    }

    @Test
    void pending_verifyOk_embedding_becomesInactive() {
    }

    @Test
    void pending_verifyFail_hetRetry_staysPending() {
    }

    @Test
    void pending_activate_rejected409() {
    }

    @Test
    void active_deactivate_becomesInactive() {
    }

    @Test
    void active_healthPermanent_becomesDisabled_correctRevision() {
    }

    @Test
    void active_healthPermanent_staleRevision_ignored() {
    }

    @Test
    void inactive_activate_verifiedAtNotNull_becomesActive() {
    }

    @Test
    void inactive_activate_neverVerified_rejected409() {
    }

    @Test
    void disabled_activate_rejected409_mustReVerifyFirst() {
    }

    @Test
    void disabled_reVerify_verifyOk_becomesActive() {
    }

    @Test
    void delete_anyStatus_becomesInactiveIsActiveFalse() {
    }

    @Test
    void recover_restoresIsActiveTrue_statusStaysInactive() {
    }

    @Test
    void recover_activateOrDeactivate_rejectedWhileNotActive() {
    }

    @Test
    void concurrentTransition_compareAndSetLosesRace_returns409_rowUnchanged() {
    }
}
