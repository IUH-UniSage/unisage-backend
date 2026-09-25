package com.unisage.backend.entity;

import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelSourceType;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.security.ApiKeyConverter;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cấu hình một model chat mà hệ thống có thể route request tới. Các trường bắt
 * buộc/tuỳ chọn phụ thuộc vào {@link #sourceType} — việc validate theo từng nhánh
 * nằm ở {@code ChatModelServiceImpl.validateBySourceType}, không phải ràng buộc ở
 * tầng entity/DB:
 *
 * <ul>
 *   <li>{@code CLOUD_API} (model của nhà cung cấp cloud, kể cả model finetune vẫn
 *       thuộc provider đó, vd {@code ft:gpt-4o-mini:...}): {@link #llmProvider} và
 *       apiKey bắt buộc; {@link #modelSourceRef} không dùng.</li>
 *   <li>{@code SELF_HOSTED} (model tự host tuỳ ý — tự train, tự convert, hoặc kéo
 *       từ HuggingFace về): {@link #modelSourceRef} tuỳ chọn và không bị ràng buộc
 *       định dạng (chuỗi tự do, vd tên model local hoặc HF repo id); apiKey tuỳ
 *       chọn (tuỳ endpoint có yêu cầu xác thực hay không); {@link #llmProvider}
 *       không dùng.</li>
 * </ul>
 *
 * Ở cả 2 trường hợp, {@link #apiBaseUrl} luôn bắt buộc — là endpoint gọi model
 * (URL của provider cho {@code CLOUD_API}, hoặc endpoint đang serve model cho
 * {@code SELF_HOSTED}).
 */
@Entity
@Table(name = "chat_models")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class ChatModel extends BaseEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * What this credential is used for. Required at creation, never changed afterwards — see
     * plan.md "Credential rotation" ("modelPurpose không được sửa sau khi tạo"); the service layer
     * enforces the immutability, there is no DB constraint for it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "model_purpose", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private ChatModelPurpose modelPurpose;

    /** Operational lifecycle — see plan.md "State machine". Every transition is a compare-and-set. */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private ChatModelStatus status = ChatModelStatus.PENDING;

    /** Set when a candidate is last successfully promoted onto this row (plan.md "Credential rotation"). */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** Bumped every time a verified candidate is promoted onto this row. 0 = never verified. */
    @Builder.Default
    @Column(name = "revision", nullable = false)
    private Integer revision = 0;

    /** Bumped every time SA creates a new candidate (edit credential / re-verify) for this row. */
    @Builder.Default
    @Column(name = "candidate_generation", nullable = false)
    private Integer candidateGeneration = 0;

    /**
     * EMBEDDING only — dimension measured at this row's last successful verify. Compared against
     * {@code embedding_index_identity} at activate/swap time; not meaningful for CHAT/EXTRACTION.
     */
    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    /**
     * EMBEDDING only — probe-sentence fingerprint measured at this row's last successful verify.
     * See plan.md "Embedding identity guard".
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "embedding_fingerprint", columnDefinition = "real[]")
    private Float[] embeddingFingerprint;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "source_type", columnDefinition = "varchar(20)")
    private ChatModelSourceType sourceType = ChatModelSourceType.CLOUD_API;

    /** Bắt buộc khi {@code sourceType == CLOUD_API} (vd "openai", "anthropic", "google"); không dùng cho {@code SELF_HOSTED}. */
    @Column(name = "llm_provider")
    private String llmProvider;

    @Column(name = "llm_model_name")
    private String llmModelName;

    /** Định danh model nguồn — chuỗi tự do, không ràng buộc định dạng; tuỳ chọn khi {@code sourceType == SELF_HOSTED}, không dùng khi {@code CLOUD_API}. */
    @Column(name = "model_source_ref")
    private String modelSourceRef;

    /** Bắt buộc khi {@code sourceType == CLOUD_API}; tuỳ chọn khi {@code SELF_HOSTED}. Được mã hoá tại {@link ApiKeyConverter}. */
    @Convert(converter = ApiKeyConverter.class)
    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    /** Endpoint gọi model — luôn bắt buộc ở cả 2 sourceType. */
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
