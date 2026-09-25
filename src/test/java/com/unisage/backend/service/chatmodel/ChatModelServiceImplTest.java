package com.unisage.backend.service.chatmodel;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.entity.ChatModel;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.ChatModelRepository;
import com.unisage.backend.utils.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelServiceImplTest {

    private ChatModelRepository chatModelRepository;
    private ChatModelServiceImpl chatModelService;

    /** Fake DNS so this stays a real unit test — no network — and a fixed test allowlist. */
    private static SsrfGuard testSsrfGuard(String allowlist) {
        SsrfGuard guard = new SsrfGuard();
        ReflectionTestUtils.setField(guard, "allowlistRaw", allowlist);
        Map<String, String> fakeDns = Map.of(
                "api.openai.com", "93.184.216.34",
                "localhost", "127.0.0.1");
        ReflectionTestUtils.setField(guard, "dnsResolver", (SsrfGuard.DnsResolver) host -> {
            String ip = fakeDns.get(host);
            if (ip == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[]{InetAddress.getByName(ip)};
        });
        return guard;
    }

    @BeforeEach
    void setUp() {
        chatModelRepository = mock(ChatModelRepository.class);
        chatModelService = new ChatModelServiceImpl(chatModelRepository, testSsrfGuard("api.openai.com,localhost"));
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

    @Test
    void create_selfHostedLocalhost_rejectedWhenAllowlistEmpty() {
        chatModelService = new ChatModelServiceImpl(chatModelRepository, testSsrfGuard(""));
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.SELF_HOSTED)
                .llmModelName("mistral-7b")
                .apiBaseUrl("http://localhost:8000/v1")
                .maxRpm(60)
                .build();

        assertThatThrownBy(() -> chatModelService.create(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_URL_NOT_ALLOWED);
    }

    @Test
    void create_cloudApiWithUnsupportedProvider_throwsProviderUnsupported() {
        ChatModelRequest request = ChatModelRequest.builder()
                .sourceType(ChatModelSourceType.CLOUD_API)
                .llmProvider("some-random-provider")
                .llmModelName("model-x")
                .apiKey("sk-abc123")
                .apiBaseUrl("https://api.openai.com/v1")
                .maxRpm(60)
                .build();

        assertThatThrownBy(() -> chatModelService.create(request))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MODEL_PROVIDER_UNSUPPORTED);
    }
}
