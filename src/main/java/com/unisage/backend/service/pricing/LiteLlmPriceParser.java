package com.unisage.backend.service.pricing;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Maps every per-token-priced entry of LiteLLM's {@code model_prices_and_context_window.json} to
 * (provider, model) prices per 1M tokens, so SA can look up any model before registering it. The
 * provider is LiteLLM's {@code litellm_provider}, except where our model registry names it
 * differently ({@code gemini} is our {@code google}). Key naming differs per provider in that file:
 * OpenAI models are bare ({@code gpt-4o-mini}), most others are prefixed with the provider
 * ({@code gemini/gemini-2.5-flash}, {@code zai/glm-4.6}) - the prefix is stripped so the name matches
 * what SA types into the model registry. Entries priced per image/second/call are skipped.
 */
@Component
@RequiredArgsConstructor
public class LiteLlmPriceParser {

    /** Anything above this per 1M tokens is treated as bad upstream data, not a real price. */
    static final BigDecimal MAX_PER_MILLION = new BigDecimal("1000");

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    /** LiteLLM's {@code litellm_provider} -> our provider id, where they differ. */
    private static final Map<String, String> PROVIDER_IDS = Map.of("gemini", "google");
    /** Column sizes of {@code model_prices}; a longer upstream name is skipped, not a failed sync. */
    private static final int MAX_PROVIDER_LENGTH = 50;
    private static final int MAX_MODEL_NAME_LENGTH = 255;

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

        // Some models appear both bare and prefixed ("deepseek-chat", "deepseek/deepseek-chat");
        // the prefixed key is the provider's own entry, so it wins.
        Map<String, ParsedPrice> prices = new LinkedHashMap<>();
        Set<String> fromPrefixedKey = new HashSet<>();
        int rejected = 0;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode spec = entry.getValue();
            String litellmProvider = spec.path("litellm_provider").asText("").trim();
            if (litellmProvider.isEmpty()) {
                continue;
            }
            String prefix = litellmProvider + "/";
            boolean prefixed = entry.getKey().startsWith(prefix);
            String modelName = modelName(prefixed ? entry.getKey().substring(prefix.length()) : entry.getKey());
            String provider = PROVIDER_IDS.getOrDefault(litellmProvider, litellmProvider);
            BigDecimal input = perMillion(spec.get("input_cost_per_token"));
            if (modelName == null || input == null || provider.length() > MAX_PROVIDER_LENGTH) {
                continue;
            }
            BigDecimal output = perMillion(spec.get("output_cost_per_token"));
            BigDecimal cached = perMillion(spec.get("cache_read_input_token_cost"));
            if (!inRange(input) || !inRange(output) || !inRange(cached)) {
                rejected++;
                continue;
            }
            String key = provider + "\u0000" + modelName;
            if (prices.containsKey(key) && (fromPrefixedKey.contains(key) || !prefixed)) {
                continue;
            }
            prices.put(key, new ParsedPrice(provider, modelName, input, output, cached,
                    deprecationDate(spec.get("deprecation_date"))));
            if (prefixed) {
                fromPrefixedKey.add(key);
            }
        }
        if (prices.isEmpty()) {
            throw new IllegalArgumentException("price map has no usable per-token prices");
        }
        return new ParseResult(new ArrayList<>(prices.values()), rejected);
    }

    private static String modelName(String name) {
        // "sample_spec" documents the file's schema; fine-tune templates ("ft:...") are not
        // registrable model names.
        if (name.isBlank() || "sample_spec".equals(name) || name.startsWith("ft:")
                || name.length() > MAX_MODEL_NAME_LENGTH) {
            return null;
        }
        return name;
    }

    /** LiteLLM writes YYYY-MM-DD; anything else is ignored rather than failing the sync. */
    private static LocalDate deprecationDate(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText().trim());
        } catch (DateTimeParseException exc) {
            return null;
        }
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
        BigDecimal cachedInputPerMillion,
        LocalDate deprecationDate
    ) {}

    public record ParseResult(List<ParsedPrice> prices, int rejected) {}
}
