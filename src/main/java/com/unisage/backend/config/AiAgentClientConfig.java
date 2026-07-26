package com.unisage.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AiAgentClientConfig {

    @Value("${app.ai-agent.url:http://localhost:8000}")
    private String aiAgentUrl;
    
    @Value("${app.ai-agent.secret-key:unisage-internal-secret-key-2026}")
    private String internalSecret;

    @Bean
    public WebClient aiAgentWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(aiAgentUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-Secret", internalSecret)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
