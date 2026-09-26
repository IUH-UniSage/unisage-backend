package com.unisage.backend.service.modelregistry;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.config.ModelRegistryIntegrationSeeder;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * See {@link ModelRegistryTestResetService}. In one transaction: reject 409 if any verification
 * job is currently {@code RUNNING} with an unexpired lease (todo.md Task 0.5 — the harness fixture
 * is responsible for waiting that out and retrying, not this endpoint); otherwise delete every
 * verification job + every {@code ChatModel} row and reseed via {@link ModelRegistryIntegrationSeeder}.
 * Deleting first means {@link ModelRegistryIntegrationSeeder#seed()}'s own "already seeded" guard
 * never blocks a deliberate reset — see that class's javadoc for the full idempotency contract.
 */
@Service
@Profile("integration")
@RequiredArgsConstructor
public class ModelRegistryTestResetServiceImpl implements ModelRegistryTestResetService {

    private final ChatModelVerificationRepository chatModelVerificationRepository;
    private final ChatModelRepository chatModelRepository;
    private final ModelRegistryIntegrationSeeder seeder;

    @Override
    @Transactional
    public void reset() {
        if (chatModelVerificationRepository.existsRunningWithUnexpiredLease()) {
            throw new AppException(ErrorCode.REGISTRY_RESET_JOB_RUNNING);
        }

        chatModelVerificationRepository.deleteAllInBatch();
        chatModelRepository.deleteAllInBatch();
        seeder.seed();
    }
}
