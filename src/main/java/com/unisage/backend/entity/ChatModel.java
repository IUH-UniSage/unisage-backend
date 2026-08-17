package com.unisage.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_models")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class ChatModel extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "llm_provider")
    private String llmProvider;

    @Column(name = "llm_model_name")
    private String llmModelName;

    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    @Column(name = "api_base_url")
    private String apiBaseUrl;

    @Column(name = "max_rpm")
    private Integer maxRpm;

    private Integer priority;

    @Builder.Default
    @Column(name = "error_count")
    private Integer errorCount = 0;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;
}
