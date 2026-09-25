package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.utils.SsrfGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatModelServiceImpl implements ChatModelService {

    /** Providers proven (ADR 0005 spike) to accept an injected http_client for SSRF pinning. */
    public static final Set<String> SUPPORTED_LLM_PROVIDERS = Set.of("openai", "anthropic");

    private final ChatModelRepository chatModelRepository;
    private final SsrfGuard ssrfGuard;

    @Override
    @Transactional
    public ChatModelResponse create(ChatModelRequest request) {
        validateBySourceType(request);

        ChatModel chatModel = ChatModel.builder()
                .sourceType(request.sourceType())
                .llmProvider(request.llmProvider())
                .llmModelName(request.llmModelName())
                .modelSourceRef(request.modelSourceRef())
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
        validateBySourceType(request);

        ChatModel chatModel = chatModelRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MODEL_NOT_FOUND));

        chatModel.setSourceType(request.sourceType());
        chatModel.setLlmProvider(request.llmProvider());
        chatModel.setLlmModelName(request.llmModelName());
        chatModel.setModelSourceRef(request.modelSourceRef());
        chatModel.setApiKeyEncrypted(request.apiKey());
        chatModel.setApiBaseUrl(request.apiBaseUrl());
        chatModel.setMaxRpm(request.maxRpm());
        chatModel.setPriority(request.priority());

        chatModel = chatModelRepository.save(chatModel);
        return mapToResponse(chatModel);
    }

    private void validateBySourceType(ChatModelRequest request) {
        if (request.sourceType() == ChatModelSourceType.CLOUD_API) {
            if (!StringUtils.hasText(request.llmProvider())) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_REQUIRED);
            }
            if (!SUPPORTED_LLM_PROVIDERS.contains(request.llmProvider().toLowerCase(Locale.ROOT))) {
                throw new AppException(ErrorCode.CHAT_MODEL_PROVIDER_UNSUPPORTED);
            }
            if (!StringUtils.hasText(request.apiKey())) {
                throw new AppException(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED);
            }
        }
        // SELF_HOSTED: apiKey và modelSourceRef đều là tuỳ chọn, modelSourceRef không bị ràng buộc định dạng.
        ssrfGuard.validate(request.apiBaseUrl());
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
                .sourceType(chatModel.getSourceType())
                .llmProvider(chatModel.getLlmProvider())
                .llmModelName(chatModel.getLlmModelName())
                .modelSourceRef(chatModel.getModelSourceRef())
                .hasApiKey(StringUtils.hasText(chatModel.getApiKeyEncrypted()))
                .apiBaseUrl(chatModel.getApiBaseUrl())
                .maxRpm(chatModel.getMaxRpm())
                .priority(chatModel.getPriority())
                .errorCount(chatModel.getErrorCount())
                .lastErrorAt(chatModel.getLastErrorAt())
                .isActive(chatModel.getIsActive())
                .createdAt(chatModel.getCreatedAt())
                .createdBy(chatModel.getCreatedBy() != null ? chatModel.getCreatedBy().getId().toString() : null)
                .createdByName(chatModel.getCreatedBy() != null ? chatModel.getCreatedBy().getFullName() : null)
                .updatedAt(chatModel.getUpdatedAt())
                .updatedBy(chatModel.getUpdatedBy() != null ? chatModel.getUpdatedBy().getId().toString() : null)
                .updatedByName(chatModel.getUpdatedBy() != null ? chatModel.getUpdatedBy().getFullName() : null)
                .build();
    }
}
