package com.unisage.backend.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Downloads LiteLLM's public price map. The URL is not a secret, but the body is untrusted:
 * size-capped here, validated row by row by {@code LiteLlmPriceParser}. */
@Component
public class LiteLlmPriceClient {

    static final int MAX_BYTES = 10 * 1024 * 1024;

    @Value("${app.model-pricing.source-url}")
    private String sourceUrl;

    @Value("${app.model-pricing.timeout-ms:15000}")
    private int timeoutMs;

    public byte[] fetch() {
        URI uri = URI.create(sourceUrl);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new PriceSourceException("price source URL must be http(s)");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        try {
            return new RestTemplate(factory).execute(uri, HttpMethod.GET, null, response -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new PriceSourceException("price source responded with " + response.getStatusCode());
                }
                return readCapped(response.getBody());
            });
        } catch (RestClientException exc) {
            throw new PriceSourceException("failed to reach price source", exc);
        }
    }

    private static byte[] readCapped(InputStream body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) {
                throw new PriceSourceException("price source body exceeds " + MAX_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static class PriceSourceException extends RuntimeException {
        public PriceSourceException(String message) {
            super(message);
        }

        public PriceSourceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
