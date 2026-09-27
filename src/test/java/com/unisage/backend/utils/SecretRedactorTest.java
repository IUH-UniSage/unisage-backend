package com.unisage.backend.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumes the shared vector file `contracts/redaction-vectors.json` — the single source of truth
 * both `unisage-backend` (Java) and `unisage-agent` (Python, `app/core/redaction.py`) test against,
 * per plan.md "Secret redaction" / "Contract files dùng chung". Follows the same
 * FileInputStream + Jackson `JsonNode` @MethodSource pattern as
 * {@code InternalNoStoreTest#implementedEndpoints()} / {@code InternalEndpointCoverageTest}.
 */
class SecretRedactorTest {

    static List<JsonNode> vectors() throws IOException {
        try (InputStream in = new java.io.FileInputStream("contracts/redaction-vectors.json")) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode vector : root.get("vectors")) {
                result.add(vector);
            }
            return result;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void matchesSharedVector(JsonNode vector) {
        String description = vector.get("description").asText();
        String input = vector.get("input").asText();
        String expected = vector.get("expected").asText();
        String knownSecret = vector.has("knownSecret") ? vector.get("knownSecret").asText() : null;

        String actual = SecretRedactor.redact(input, knownSecret);

        assertThat(actual).as(description).isEqualTo(expected);
    }

    @Test
    void nullInput_returnsEmptyString() {
        assertThat(SecretRedactor.redact(null)).isEmpty();
        assertThat(SecretRedactor.redact(null, "sk-whatever12345")).isEmpty();
    }

    @Test
    void emptyInput_returnsEmptyString() {
        assertThat(SecretRedactor.redact("")).isEmpty();
    }

    @Test
    void blankOrNullKnownSecret_doesNotThrow_andGenericPatternsStillApply() {
        assertThat(SecretRedactor.redact("Bearer sk-abcdefghijklmno", null))
                .isEqualTo("[REDACTED]");
        assertThat(SecretRedactor.redact("Bearer sk-abcdefghijklmno", "   "))
                .isEqualTo("[REDACTED]");
        assertThat(SecretRedactor.redact("Bearer sk-abcdefghijklmno", ""))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void singleArgOverload_behavesLikeNullKnownSecret() {
        String text = "GET https://x.com/path?token=abcde12345";
        assertThat(SecretRedactor.redact(text)).isEqualTo(SecretRedactor.redact(text, null));
    }

    @Test
    void resultIsNeverLongerThan500Chars() {
        String longText = "y".repeat(10_000);
        assertThat(SecretRedactor.redact(longText)).hasSize(500);
    }

    @Test
    void toStringOfExceptionIsNeverThePassthroughPath() {
        // Defense in depth check colocated with the redactor itself: an unredacted secret handed
        // straight through must not survive a redact() call — this is the property the "helper is
        // the only way exception text becomes DB/Slack/HTTP text" acceptance criterion depends on.
        Exception exception = new RuntimeException("upstream said: Authorization: Bearer sk-realtoken1234567890abcdef");
        assertThat(SecretRedactor.redact(exception.getMessage()))
                .doesNotContain("sk-realtoken1234567890abcdef");
    }
}
