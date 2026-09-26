package com.unisage.backend.dto.request;

import com.unisage.backend.entity.enums.ChatModelSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * {@code PUT /chat-models/{id}} body — split from {@link ChatModelRequest} because create and
 * update need different {@code apiKey} semantics (plan.md "Credential rotation"). A plain
 * {@code String} can't tell "field absent from the JSON" apart from {@code "apiKey": null}, but
 * update has to: both mean "keep the old key", which is different from {@code ""} (400) and from
 * a new value (becomes a rotation candidate). {@link JsonNullable} is the only field on this DTO
 * with that tri/four-state shape — every other field keeps its plain type because it either has
 * no "absent" case that matters (required) or applies immediately regardless (priority, maxRpm).
 *
 * <p>All 4 states are resolved in exactly one place — {@code ChatModelServiceImpl.update} — not
 * here and not in a mapper, per plan.md ("Xử lý 4 trạng thái ở 1 chỗ duy nhất"). The compact
 * constructor only normalizes a Java {@code null} (e.g. from a test building this record directly
 * without going through Jackson) to {@code undefined()} — Jackson itself, with
 * {@code JsonNullableModule} registered, already produces {@code undefined()} for a JSON body
 * that omits the field.
 */
@Builder
public record ChatModelUpdateRequest(
    @NotNull(message = "sourceType không được để trống")
    ChatModelSourceType sourceType,

    String llmProvider,

    @NotBlank(message = "llmModelName không được để trống")
    String llmModelName,

    String modelSourceRef,

    JsonNullable<String> apiKey,

    /** Only valid when the (possibly just-changed) {@code sourceType} is {@code SELF_HOSTED}. */
    Boolean clearApiKey,

    @NotBlank(message = "apiBaseUrl không được để trống")
    String apiBaseUrl,

    @NotNull(message = "maxRpm không được để trống")
    Integer maxRpm,

    Integer priority
) {
    public ChatModelUpdateRequest {
        if (apiKey == null) {
            apiKey = JsonNullable.undefined();
        }
    }
}
