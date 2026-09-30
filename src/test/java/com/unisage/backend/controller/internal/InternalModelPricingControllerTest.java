package com.unisage.backend.controller.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalModelPricingControllerTest {

    private static final String SECRET = "unisage-internal-secret-key-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.internal.allowed-cidrs", () -> "127.0.0.1/32,::1/128");
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM model_prices");
    }

    @Test
    void withoutOrWithWrongSecret_returns403() {
        HttpHeaders wrong = new HttpHeaders();
        wrong.add("X-Internal-Secret", "not-the-real-secret");

        assertThat(get(new HttpHeaders()).getStatusCode().value()).isEqualTo(403);
        assertThat(get(wrong).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecret_returnsVersionAndPrices() throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        jdbcTemplate.update("""
                INSERT INTO model_prices (id, provider, model_name, input_per_million, output_per_million,
                                          source, created_at, updated_at)
                VALUES (?, 'openai', 'gpt-4o-mini', ?, ?, 'LITELLM', ?, ?)
                """, UUID.randomUUID(), new BigDecimal("0.15"), new BigDecimal("0.6"), now, now);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Secret", SECRET);

        ResponseEntity<String> response = get(headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.has("version")).isTrue();
        JsonNode price = body.get("prices").get(0);
        assertThat(price.get("provider").asText()).isEqualTo("openai");
        assertThat(price.get("modelName").asText()).isEqualTo("gpt-4o-mini");
        assertThat(price.get("inputPerMillion").decimalValue()).isEqualByComparingTo("0.15");
        assertThat(price.get("cachedInputPerMillion").isNull()).isTrue();
    }

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange("http://localhost:" + port + "/api/v1/internal/model-pricing/snapshot",
                HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }
}
