package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ChatModelService {

    ChatModelResponse create(ChatModelRequest request);

    ChatModelResponse update(UUID id, ChatModelRequest request);

    ChatModelResponse getById(UUID id);

    PageResponse<List<ChatModelResponse>> getAll(Pageable pageable);

    void delete(UUID id);

    void recover(UUID id);
}
