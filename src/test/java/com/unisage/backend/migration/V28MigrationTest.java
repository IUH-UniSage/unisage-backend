package com.unisage.backend.migration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway has already applied V28 on the empty test DB, so the script is re-run against seeded
 * rows - it is written to be idempotent. */
class V28MigrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM chat_model_verifications");
        jdbcTemplate.update("DELETE FROM chat_models");
    }

    @Test
    void deactivatesGroqAndMistralAndCancelsTheirOpenJobs() throws Exception {
        UUID groq = insertModel("groq", "ACTIVE");
        UUID mistral = insertModel("Mistral", "PENDING");
        UUID openai = insertModel("openai", "ACTIVE");
        UUID rotatingOpenai = insertModel("openai", "ACTIVE");
        UUID groqJob = insertJob(groq, null);
        UUID openaiJob = insertJob(openai, null);
        UUID rotationToGroqJob = insertJob(rotatingOpenai, "groq");
        long versionBefore = registryVersion();

        runMigration();

        assertThat(status("chat_models", groq)).isEqualTo("INACTIVE");
        assertThat(status("chat_models", mistral)).isEqualTo("INACTIVE");
        assertThat(status("chat_models", openai)).isEqualTo("ACTIVE");
        assertThat(status("chat_model_verifications", groqJob)).isEqualTo("CANCELLED");
        assertThat(status("chat_model_verifications", rotationToGroqJob)).isEqualTo("CANCELLED");
        assertThat(status("chat_model_verifications", openaiJob)).isEqualTo("QUEUED");
        assertThat(registryVersion()).isEqualTo(versionBefore + 1);
    }

    private void runMigration() throws Exception {
        String sql = new ClassPathResource("db/migration/V28__deactivate_unsupported_providers.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);
    }

    private UUID insertModel(String provider, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO chat_models (id, model_purpose, status, source_type, llm_provider, llm_model_name,
                                         api_base_url, max_rpm, is_active, revision, candidate_generation)
                VALUES (?, 'CHAT', ?, 'CLOUD_API', ?, 'model-x', 'https://example.com/v1', 60, true, 0, 0)
                """, id, status, provider);
        return id;
    }

    private UUID insertJob(UUID chatModelId, String candidateProvider) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO chat_model_verifications (id, chat_model_id, status, candidate_generation,
                                                      base_revision, candidate_llm_provider)
                VALUES (?, ?, 'QUEUED', 1, 0, ?)
                """, id, chatModelId, candidateProvider);
        return id;
    }

    private String status(String table, UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private long registryVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM model_registry_version WHERE id = 1", Long.class);
    }
}
