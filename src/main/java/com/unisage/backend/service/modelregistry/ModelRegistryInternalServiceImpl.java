package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.request.internal.CredentialHealthReportRequest;
import com.unisage.backend.dto.response.internal.CredentialHealthReportResponse;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.utils.SecretRedactor;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModelRegistryInternalServiceImpl implements ModelRegistryInternalService {

    private final ChatModelRepository chatModelRepository;

    /**
     * Backs plan.md "Internal API contract" endpoint #3. The revision check and the
     * counter/last-error write happen in one {@code UPDATE ... WHERE revision = :rev} (see
     * {@link ChatModelRepository#recordHealthError}) — a stale {@code credentialRevision} updates 0
     * rows and the whole report is ignored (no counter, no status change), same transaction, no
     * separate read first.
     */
    @Override
    @Transactional
    public CredentialHealthReportResponse reportHealth(UUID chatModelId, CredentialHealthReportRequest request) {
        if (!chatModelRepository.existsById(chatModelId)) {
            throw new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND);
        }

        LocalDateTime occurredAt = request.occurredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        // Redact even though unisage-agent already redacts its side — defense in depth, plan.md
        // "Secret redaction". SecretRedactor.redact also truncates to 500 chars, matching the column.
        String redactedMessage = request.message() != null ? SecretRedactor.redact(request.message()) : null;

        int applied = chatModelRepository.recordHealthError(
                chatModelId, request.credentialRevision(), occurredAt, request.errorCode(), redactedMessage);
        if (applied == 0) {
            // Stale revision — report ignored entirely, per plan.md R2.6.
            return new CredentialHealthReportResponse(false);
        }

        return new CredentialHealthReportResponse(true);
    }
}
