package com.unisage.backend.service.pricing;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Maps LiteLLM's {@code model_prices_and_context_window.json} to (provider, model) prices per 1M
 * tokens. Key naming differs per provider in that file: OpenAI models are bare
 * ({@code gpt-4o-mini}), Gemini API models are prefixed ({@code gemini/gemini-2.5-flash}) - the
 * prefix is stripped so the name matches what SA types into the model registry.
 */
@Component
@RequiredArgsConstructor
public class LiteLlmPriceParser {

    /** Anything above this per 1M tokens is treated as bad upstream data, not a real price. */
    static final BigDecimal MAX_PER_MILLION = new BigDecimal("1000");

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final Set<String> MODES = Set.of("chat", "embedding");
    /** LiteLLM's {@code litellm_provider} -> our provider id, and the key prefix to strip. */
    private static final Map<String, String[]> PROVIDERS = Map.of(
            "openai", new String[] {"openai", ""},
            "gemini", new String[] {"google", "gemini/"});

    private final ObjectMapper objectMapper;

    public ParseResult parse(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException exc) {
            throw new IllegalArgumentException("price map is not valid JSON", exc);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("price map root is not a JSON object");
        }

        List<ParsedPrice> prices = new ArrayList<>();
        int rejected = 0;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode spec = entry.getValue();
            String[] mapping = PROVIDERS.get(spec.path("litellm_provider").asText(""));
            if (mapping == null || !MODES.contains(spec.path("mode").asText(""))) {
                continue;
            }
            String modelName = modelName(entry.getKey(), mapping[1]);
            if (modelName == null) {
                continue;
            }
            BigDecimal input = perMillion(spec.get("input_cost_per_token"));
            BigDecimal output = perMillion(spec.get("output_cost_per_token"));
            BigDecimal cached = perMillion(spec.get("cache_read_input_token_cost"));
            if (input == null || !inRange(input) || !inRange(output) || !inRange(cached)) {
                rejected++;
                continue;
            }
            prices.add(new ParsedPrice(mapping[0], modelName, input, output, cached));
        }
        if (prices.isEmpty()) {
            throw new IllegalArgumentException("price map has no usable openai/google entries");
        }
        return new ParseResult(prices, rejected);
    }

    private static String modelName(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            return null;
        }
        String name = key.substring(prefix.length());
        // "sample_spec" documents the file's schema; fine-tune templates ("ft:...") and nested routes
        // ("a/b") are not registrable model names.
        if (name.isBlank() || "sample_spec".equals(name) || name.contains("/") || name.startsWith("ft:")) {
            return null;
        }
        return name;
    }

    private static BigDecimal perMillion(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.decimalValue().multiply(ONE_MILLION).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static boolean inRange(BigDecimal value) {
        return value == null || (value.signum() >= 0 && value.compareTo(MAX_PER_MILLION) <= 0);
    }

    public record ParsedPrice(
        String provider,
        String modelName,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cachedInputPerMillion
    ) {}

    public record ParseResult(List<ParsedPrice> prices, int rejected) {}
}
