package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.request.ChatModelUpdateRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ChatModelService {

    ChatModelResponse create(ChatModelRequest request);

    ChatModelResponse update(UUID id, ChatModelUpdateRequest request);

    ChatModelResponse getById(UUID id);

    PageResponse<List<ChatModelResponse>> getAll(ChatModelPurpose modelPurpose, ChatModelStatus status, Pageable pageable);

    void delete(UUID id);

    void recover(UUID id);

    /** {@code PATCH /chat-models/{id}/status} — plan.md "State machine"; only ACTIVE/INACTIVE are SA-triggerable. */
    ChatModelResponse updateStatus(UUID id, ChatModelStatus targetStatus);

    /** {@code PATCH /chat-models/{id}/priority} — applies immediately, no verify needed. */
    ChatModelResponse updatePriority(UUID id, Integer priority);

    /** {@code POST /chat-models/{id}/verify} — new candidate job for the row's current values; supersedes any open job. */
    ChatModelResponse verify(UUID id);
}
