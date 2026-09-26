package com.unisage.backend.service.chatmodel;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.utils.SsrfGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * State-transition matrix from plan.md "State machine". Names double as the scenario list.
 * Only {@code delete}/{@code recover} are implemented as of Task 1 — the rest need the
 * activate/deactivate/verify service methods that land in Task 3, so they stay disabled
 * individually (not class-level) so this file keeps compiling as each one is wired up.
 */
class ChatModelStateTransitionTest {

    private ChatModelRepository chatModelRepository;
    private ChatModelServiceImpl chatModelService;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelService = new ChatModelServiceImpl(chatModelRepository, new SsrfGuard());
        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ChatModel modelWith(ChatModelStatus status, boolean isActive) {
        ChatModel model = ChatModel.builder()
                .id(UUID.randomUUID())
                .modelPurpose(ChatModelPurpose.CHAT)
                .status(status)
                .revision(1)
                .candidateGeneration(1)
                .build();
        model.setIsActive(isActive);
        return model;
    }

    @Test
    @Disabled("Task 3 — needs the verify/promote service")
    void create_startsAtPending_revisionZero() {
    }

    @Test
    @Disabled("Task 3 — needs the verify/promote service")
    void pending_verifyOk_chatOrExtraction_becomesActive() {
    }

    @Test
    @Disabled("Task 3 — needs the verify/promote service")
    void pending_verifyOk_embedding_becomesInactive() {
    }

    @Test
    @Disabled("Task 3 — needs the verify/promote service")
    void pending_verifyFail_hetRetry_staysPending() {
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints")
    void pending_activate_rejected409() {
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints")
    void active_deactivate_becomesInactive() {
    }

    @Test
    @Disabled("Task 2 — needs the health-report endpoint")
    void active_healthPermanent_becomesDisabled_correctRevision() {
    }

    @Test
    @Disabled("Task 2 — needs the health-report endpoint")
    void active_healthPermanent_staleRevision_ignored() {
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints")
    void inactive_activate_verifiedAtNotNull_becomesActive() {
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints")
    void inactive_activate_neverVerified_rejected409() {
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints")
    void disabled_activate_rejected409_mustReVerifyFirst() {
    }

    @Test
    @Disabled("Task 3 — needs the verify/promote service")
    void disabled_reVerify_verifyOk_becomesActive() {
    }

    @Test
    void delete_anyStatus_becomesInactiveIsActiveFalse() {
        for (ChatModelStatus status : Set.of(
                ChatModelStatus.PENDING, ChatModelStatus.ACTIVE, ChatModelStatus.INACTIVE, ChatModelStatus.DISABLED)) {
            ChatModel model = modelWith(status, true);
            when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

            chatModelService.delete(model.getId());

            assertThat(model.getStatus()).isEqualTo(ChatModelStatus.INACTIVE);
            assertThat(model.getIsActive()).isFalse();
        }
    }

    @Test
    void recover_restoresIsActiveTrue_statusStaysInactive() {
        ChatModel model = modelWith(ChatModelStatus.INACTIVE, false);
        when(chatModelRepository.findById(model.getId())).thenReturn(Optional.of(model));

        chatModelService.recover(model.getId());

        assertThat(model.getIsActive()).isTrue();
        assertThat(model.getStatus()).isEqualTo(ChatModelStatus.INACTIVE);
    }

    @Test
    @Disabled("Task 3 — needs the SA activate/deactivate endpoints to assert against")
    void recover_activateOrDeactivate_rejectedWhileNotActive() {
    }

    @Test
    @Disabled("Task 3 — needs a compare-and-set transition service to race against")
    void concurrentTransition_compareAndSetLosesRace_returns409_rowUnchanged() {
    }
}
