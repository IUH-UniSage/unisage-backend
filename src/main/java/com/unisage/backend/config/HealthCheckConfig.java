package com.unisage.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * UNISAGE-62: a dedicated, short-timeout {@link RestTemplate} for the health-check
 * {@code HealthIndicator}s (gateway/agent reachability probes). A hanging dependency must not
 * hang {@code GET /admin/health}, so both connect and read timeouts are capped well below the
 * FE's polling interval — see {@code app.health-check.timeout-ms}.
 */
@Configuration
public class HealthCheckConfig {

    @Bean
    public RestTemplate healthCheckRestTemplate(
            @Value("${app.health-check.timeout-ms:2500}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}
