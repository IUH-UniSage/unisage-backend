package com.unisage.backend.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards against the 3 copies of the 7 verification statuses (plan.md "Verification lifecycle")
 * drifting apart: the Java enum (source of truth), the V18 CHECK constraint, and Jackson's binding
 * of the enum used by every SA-facing DTO field of this type (e.g. a future
 * {@code latestVerification.status}).
 */
@Testcontainers
@SpringBootTest
class VerificationStatusContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("DB_NAME", POSTGRES::getDatabaseName);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    /** plan.md's exact order — the single authoritative list every other copy must match. */
    private static final List<String> EXPECTED_ORDER = List.of(
            "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "SUPERSEDED", "CANCELLED", "REINDEX_REQUIRED");

    @Test
    void javaEnum_matchesExpectedOrder() {
        List<String> actual = Arrays.stream(ChatModelVerificationStatus.values()).map(Enum::name).toList();
        assertThat(actual).containsExactlyElementsOf(EXPECTED_ORDER);
    }

    @Test
    void generatedContractFile_matchesEnum() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("contracts", "verification-statuses.json"))) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<String> fromFile = new ArrayList<>();
            root.forEach(node -> fromFile.add(node.asText()));
            assertThat(fromFile).containsExactlyElementsOf(EXPECTED_ORDER);
        }
    }

    @Test
    void dbCheckConstraint_allowsExactlySameValues() throws SQLException {
        Pattern valuePattern = Pattern.compile("'([A-Z_]+)'::character varying");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint "
                             + "WHERE conrelid = 'chat_model_verifications'::regclass "
                             + "AND conname = 'chat_model_verifications_status_check'")) {
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).as("CHECK constraint chat_model_verifications_status_check must exist").isTrue();

            String def = rs.getString("def");
            Matcher matcher = valuePattern.matcher(def);
            List<String> allowed = new ArrayList<>();
            while (matcher.find()) {
                allowed.add(matcher.group(1));
            }
            assertThat(allowed).containsExactlyInAnyOrderElementsOf(EXPECTED_ORDER);
        }
    }

    @Test
    void jacksonBinding_acceptsOnlyTheSeven() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : EXPECTED_ORDER) {
            ChatModelVerificationStatus parsed = mapper.readValue("\"" + value + "\"", ChatModelVerificationStatus.class);
            assertThat(parsed.name()).isEqualTo(value);
        }

        assertThatThrownBy(() -> mapper.readValue("\"NOT_A_REAL_STATUS\"", ChatModelVerificationStatus.class))
                .isInstanceOf(JsonProcessingException.class);
    }
}
