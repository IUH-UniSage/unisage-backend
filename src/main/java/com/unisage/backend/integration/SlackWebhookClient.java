package com.unisage.backend.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Posts one plain-text message to a Slack Incoming Webhook. The webhook URL is a bearer
 * credential by itself (anyone holding it can post to the channel) - it must never appear in a
 * log line, an exception message, or anywhere this client's own errors surface.
 */
@Component
public class SlackWebhookClient {

    @Value("${app.budget-alert.slack.enabled:false}")
    private boolean enabled;

    @Value("${app.budget-alert.slack.webhook-url:}")
    private String webhookUrl;

    @Value("${app.budget-alert.slack.timeout-ms:3000}")
    private int timeoutMs;

    /** True iff a send would actually attempt a request - enabled AND a non-blank webhook URL. */
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(webhookUrl);
    }

    /**
     * Posts {@code message} to the configured webhook. Throws {@link IllegalStateException} if
     * not configured (callers must check {@link #isConfigured()} first) or
     * {@link SlackDeliveryException} if Slack rejects the request or is unreachable - neither
     * exception message ever includes {@link #webhookUrl}.
     */
    public void send(String message) {
        if (!isConfigured()) {
            throw new IllegalStateException("Slack webhook is not configured");
        }
        RestTemplate restTemplate = buildRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"text\":" + toJsonString(message) + "}", headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new SlackDeliveryException("Slack webhook responded with " + response.getStatusCode());
            }
        } catch (RestClientException exc) {
            throw new SlackDeliveryException("failed to reach Slack webhook", exc);
        }
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private static String toJsonString(String value) {
        // Slack's payload is a single "text" field - a hand-rolled escape is enough here and
        // avoids pulling in a JSON mapper dependency just for one field.
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
        return "\"" + escaped + "\"";
    }

    public static class SlackDeliveryException extends RuntimeException {
        public SlackDeliveryException(String message) {
            super(message);
        }

        public SlackDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
