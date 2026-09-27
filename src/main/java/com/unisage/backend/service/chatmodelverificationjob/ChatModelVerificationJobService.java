package com.unisage.backend.service.chatmodelverificationjob;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.unisage.backend.dto.response.ChatModelVerificationJobResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

/** Admin "Jobs" tab — read-only view over {@code chat_model_verifications}, no write operations. */
public interface ChatModelVerificationJobService {

    /** {@code GET /chat-models/verifications} — {@code chatModelId}/{@code status} filters are both optional. */
    PageResponse<List<ChatModelVerificationJobResponse>> getAll(
            UUID chatModelId, ChatModelVerificationStatus status, Pageable pageable);

    /** {@code GET /chat-models/verifications/{jobId}} */
    ChatModelVerificationJobResponse getById(UUID jobId);
}
