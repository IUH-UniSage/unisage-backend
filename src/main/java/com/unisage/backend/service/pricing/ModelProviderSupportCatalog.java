package com.unisage.backend.service.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads {@code model-provider-support.yml}: which of our chat-model providers can call each priced
 * model. Hand-maintained and committed - the price sync never writes it. A malformed file fails
 * startup instead of silently showing every model as unsupported.
 */
@Component
public class ModelProviderSupportCatalog {

    static final String RESOURCE = "model-provider-support.yml";
    private static final Set<String> STATUSES = Set.of("tested", "paid", "restricted", "unsupported");

    private final Map<String, List<String>> providerDefaults = new HashMap<>();
    private final Map<String, Support> models = new HashMap<>();

    /**
     * @param status {@code tested}, {@code paid}, {@code restricted}, {@code unsupported}, or
     *     {@code inferred} when only the provider-level default applies
     * @param deprecated the provider API itself reported the model as retired
     */
    public record Support(List<String> providers, String status, String note, boolean deprecated) {}

    public ModelProviderSupportCatalog() {
        this(new ClassPathResource(RESOURCE));
    }

    ModelProviderSupportCatalog(ClassPathResource resource) {
        Map<String, Object> root;
        try (InputStream in = resource.getInputStream()) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        } catch (IOException exc) {
            throw new IllegalStateException("cannot read " + resource.getPath(), exc);
        }
        if (root == null) {
            throw new IllegalStateException(resource.getPath() + " is empty");
        }
        mapOf(root.get("providers"), "providers").forEach((provider, value) ->
                providerDefaults.put(normalize(provider), stringList(value, "providers." + provider)));
        mapOf(root.get("models"), "models").forEach((key, value) -> {
            Map<String, Object> entry = mapOf(value, "models." + key);
            String status = String.valueOf(entry.get("status"));
            if (!STATUSES.contains(status)) {
                throw new IllegalStateException("models." + key + ": unknown status " + status);
            }
            Object note = entry.get("note");
            models.put(normalize(key), new Support(
                    stringList(entry.getOrDefault("providers", List.of()), "models." + key + ".providers"),
                    status,
                    note == null ? null : note.toString(),
                    Boolean.TRUE.equals(entry.get("is_deprecated"))));
        });
    }

    /** The model's own entry when probed, else its provider's default. */
    public Support lookup(String provider, String modelName) {
        Support probed = models.get(normalize(provider + "/" + modelName));
        if (probed != null) {
            return probed;
        }
        List<String> providers = providerDefaults.getOrDefault(normalize(provider), List.of());
        return new Support(providers, providers.isEmpty() ? "unsupported" : "inferred", null, false);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException(RESOURCE + ": " + path + " must be a mapping");
        }
        return (Map<String, Object>) value;
    }

    private static List<String> stringList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(RESOURCE + ": " + path + " must be a list");
        }
        return list.stream().map(item -> normalize(String.valueOf(item))).toList();
    }
}
