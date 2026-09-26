package com.unisage.backend.service.modelregistry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse;
import com.unisage.backend.dto.response.internal.InternalVerificationClaimResponse.CandidateCredential;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.ChatModelVerification;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;
import com.unisage.backend.repository.ChatModelVerificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * See {@link ChatModelVerificationClaimService}. {@code findClaimableIds} locks its rows
 * {@code FOR UPDATE SKIP LOCKED} inside this same transaction, so two concurrent callers can never
 * select the same id; the actual claim write (fresh lease token, RUNNING, attempt += 1) happens
 * here on those already-locked rows.
 */
@Service
@RequiredArgsConstructor
public class ChatModelVerificationClaimServiceImpl implements ChatModelVerificationClaimService {

    private static final long LEASE_SECONDS = 60;

    private final ChatModelVerificationRepository chatModelVerificationRepository;

    @Override
    @Transactional
    public List<InternalVerificationClaimResponse> claim(int limit) {
        List<UUID> claimableIds = chatModelVerificationRepository.findClaimableIds(limit);

        List<InternalVerificationClaimResponse> claimed = new ArrayList<>(claimableIds.size());
        for (UUID id : claimableIds) {
            // Already locked by findClaimableIds's FOR UPDATE SKIP LOCKED — this is a plain read of
            // the same row within the same transaction, not a second lock acquisition.
            ChatModelVerification job = chatModelVerificationRepository.findById(id).orElseThrow(() ->
                    new IllegalStateException("Claimable job vanished mid-transaction: " + id));

            LocalDateTime now = LocalDateTime.now();
            UUID freshLeaseToken = UUID.randomUUID();
            job.setStatus(ChatModelVerificationStatus.RUNNING);
            job.setLeaseToken(freshLeaseToken);
            job.setLeaseUntil(now.plusSeconds(LEASE_SECONDS));
            job.setAttempt(job.getAttempt() + 1);
            if (job.getStartedAt() == null) {
                job.setStartedAt(now);
            }
            chatModelVerificationRepository.save(job);

            claimed.add(toClaimResponse(job));
        }
        return claimed;
    }

    private InternalVerificationClaimResponse toClaimResponse(ChatModelVerification job) {
        ChatModel model = job.getChatModel();
        CandidateCredential credential = new CandidateCredential(
                model.getId(),
                model.getModelPurpose(),
                model.getSourceType(),
                job.getCandidateLlmProvider(),
                job.getCandidateLlmModelName(),
                job.getCandidateModelSourceRef(),
                job.getCandidateApiBaseUrl(),
                job.getCandidateApiKeyEncrypted(),
                model.getMaxRpm());
        return new InternalVerificationClaimResponse(
                job.getId(), job.getLeaseToken(), job.getAttempt(), job.getLeaseUntil(), credential);
    }
}
