package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelServiceImplTest {

    private ChatModelRepository chatModelRepository;
    private ChatModelServiceImpl chatModelService;

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelService = new ChatModelServiceImpl(chatModelRepository);
        when(chatModelRepository.save(any(ChatModel.class))).thenAnswer(invocation -> {
            ChatModel entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    @Test
    void create_cloudApiWithoutApiKey_throwsApiKeyRequired() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .build();

        assertThatThrownBy(() -> chatModelService.create(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_API_KEY_REQUIRED);
    }

    @Test
    void create_cloudApiWithoutProvider_throwsProviderRequired() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmModelName("gpt-4o-mini")
                .apiKey("sk-abc123")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .build();

        assertThatThrownBy(() -> chatModelService.create(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_PROVIDER_REQUIRED);
    }

    @Test
    void create_cloudApiWithProviderAndApiKey_succeeds() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("openai")
                .llmModelName("gpt-4o-mini")
                .apiKey("sk-abc123")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .build();

        var response = chatModelService.create(request);

        assertThat(response.sourceType()).isEqualTo(ChatModelSourceType.CLOUD_API);
        assertThat(response.hasApiKey()).isTrue();
    }

    @Test
    void create_selfHostedWithoutApiKeyOrModelSourceRef_succeeds() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.SELF_HOSTED)
                .llmModelName("mistral-7b")
                .apiBaseUrl("http://localhost:8000/v1")
                .maxRpm(60)
                .build();

        var response = chatModelService.create(request);

        assertThat(response.sourceType()).isEqualTo(ChatModelSourceType.SELF_HOSTED);
        assertThat(response.hasApiKey()).isFalse();
    }

    @Test
    void create_selfHostedWithHuggingFaceRepoIdAsModelSourceRef_succeeds() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.SELF_HOSTED)
                .llmModelName("mistral-7b")
                .modelSourceRef("mistralai/Mistral-7B-Instruct-v0.3")
                .apiBaseUrl("http://localhost:8000/v1")
                .maxRpm(60)
                .build();

        var response = chatModelService.create(request);

        assertThat(response.sourceType()).isEqualTo(ChatModelSourceType.SELF_HOSTED);
        assertThat(response.modelSourceRef()).isEqualTo("mistralai/Mistral-7B-Instruct-v0.3");
        assertThat(response.hasApiKey()).isFalse();
    }

    @Test
    void create_selfHostedWithFreeFormModelSourceRef_succeeds() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.SELF_HOSTED)
                .llmModelName("my-custom-model")
                .modelSourceRef("any free-form name !!")
                .apiBaseUrl("http://localhost:8001/v1")
                .maxRpm(60)
                .build();

        var response = chatModelService.create(request);

        assertThat(response.sourceType()).isEqualTo(ChatModelSourceType.SELF_HOSTED);
        assertThat(response.modelSourceRef()).isEqualTo("any free-form name !!");
        assertThat(response.hasApiKey()).isFalse();
    }
}
