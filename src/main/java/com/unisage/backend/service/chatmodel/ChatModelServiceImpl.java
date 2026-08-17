package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatModelServiceImpl implements ChatModelService {

    private final ChatModelRepository chatModelRepository;

    @Override
    @Transactional
    public ChatModelResponse create(ChatModelRequest request) {
        ChatModel chatModel = ChatModel.builder()
                .llmProvider(request.llmProvider())
                .llmModelName(request.llmModelName())
                .apiKeyEncrypted(request.apiKey())
                .apiBaseUrl(request.apiBaseUrl())
                .maxRpm(request.maxRpm())
                .priority(request.priority())
                .errorCount(0)
                .build();
        chatModel = chatModelRepository.save(chatModel);
        return mapToResponse(chatModel);
    }

    @Override
    @Transactional
    public ChatModelResponse update(UUID id, ChatModelRequest request) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        chatModel.setLlmProvider(request.llmProvider());
        chatModel.setLlmModelName(request.llmModelName());
        chatModel.setApiKeyEncrypted(request.apiKey());
        chatModel.setApiBaseUrl(request.apiBaseUrl());
        chatModel.setMaxRpm(request.maxRpm());
        chatModel.setPriority(request.priority());

        chatModel = chatModelRepository.save(chatModel);
        return mapToResponse(chatModel);
    }

    @Override
    public ChatModelResponse getById(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        return mapToResponse(chatModel);
    }

    @Override
    public PageResponse<List<ChatModelResponse>> getAll(Pageable pageable) {
        Page<ChatModel> page = chatModelRepository.findAll(pageable);
        return PageResponse.fromPage(page, this::mapToResponse);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        chatModel.setIsActive(false);
        chatModelRepository.save(chatModel);
    }

    @Override
    @Transactional
    public void recover(UUID id) {
        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));
        chatModel.setIsActive(true);
        chatModelRepository.save(chatModel);
    }

    private ChatModelResponse mapToResponse(ChatModel chatModel) {
        return ChatModelResponse.builder()
                .id(chatModel.getId())
                .llmProvider(chatModel.getLlmProvider())
                .llmModelName(chatModel.getLlmModelName())
                .apiBaseUrl(chatModel.getApiBaseUrl())
                .maxRpm(chatModel.getMaxRpm())
                .priority(chatModel.getPriority())
                .errorCount(chatModel.getErrorCount())
                .lastErrorAt(chatModel.getLastErrorAt())
                .isActive(chatModel.getIsActive())
                .createdAt(chatModel.getCreatedAt())
                .createdBy(chatModel.getCreatedBy())
                .updatedAt(chatModel.getUpdatedAt())
                .updatedBy(chatModel.getUpdatedBy())
                .build();
    }
}
