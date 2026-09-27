package com.unisage.backend.service.chatmodelverificationjob;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.unisage.backend.dto.response.ChatModelVerificationJobResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChatModelVerificationJobServiceImpl implements ChatModelVerificationJobService {

    private final ChatModelVerificationRepository chatModelVerificationRepository;

    @Override
    public PageResponse<List<ChatModelVerificationJobResponse>> getAll(
            UUID chatModelId, ChatModelVerificationStatus status, Pageable pageable) {
        Page<ChatModelVerification> page =
                chatModelVerificationRepository.findAllFiltered(chatModelId, status, pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    public ChatModelVerificationJobResponse getById(UUID jobId) {
        ChatModelVerification job = chatModelVerificationRepository.findByIdWithChatModel(jobId)
                .orElseThrow(() -> new AppException(ErrorCode.VERIFICATION_JOB_NOT_FOUND));
        return mapToResponse(job);
    }

    private ChatModelVerificationJobResponse mapToResponse(ChatModelVerification job) {
        return ChatModelVerificationJobResponse.builder()
                .id(job.getId())
                .chatModelId(job.getChatModel().getId())
                .modelPurpose(job.getChatModel().getModelPurpose())
                .status(job.getStatus())
                .candidateGeneration(job.getCandidateGeneration())
                .baseRevision(job.getBaseRevision())
                .candidateLlmProvider(job.getCandidateLlmProvider())
                .candidateLlmModelName(job.getCandidateLlmModelName())
                .candidateModelSourceRef(job.getCandidateModelSourceRef())
                .candidateApiBaseUrl(job.getCandidateApiBaseUrl())
                .hasCandidateApiKey(StringUtils.hasText(job.getCandidateApiKeyEncrypted()))
                .embeddingDimension(job.getEmbeddingDimension())
                .attempt(job.getAttempt())
                .maxAttempts(job.getMaxAttempts())
                .nextAttemptAt(job.getNextAttemptAt())
                .leaseUntil(job.getLeaseUntil())
                .errorType(job.getErrorType())
                .errorCode(job.getErrorCode())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }
}
