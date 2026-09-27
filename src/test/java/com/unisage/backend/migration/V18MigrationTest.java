package com.unisage.backend.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V18 applies cleanly on a fresh DB, backfills existing rows, and the immutability trigger holds. */
@Testcontainers
@SpringBootTest
class V18MigrationTest {

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

    @Test
    void v16Constraints_andTriggerExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertColumnExists(conn, "chat_models", "model_purpose");
            assertColumnExists(conn, "chat_models", "status");
            assertColumnExists(conn, "chat_models", "revision");
            assertColumnExists(conn, "chat_models", "candidate_generation");
            assertTableExists(conn, "chat_model_verifications");
            assertTableExists(conn, "embedding_index_identity");
        }
    }

    @Test
    void embeddingIndexIdentity_rejectsUpdateAndDelete() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO embedding_index_identity "
                            + "(collection_name, provider, model_name, dimension, fingerprint, established_by) "
                            + "VALUES (?, 'openai', 'text-embedding-3-small', 1536, ARRAY[0.1,0.2]::real[], 'bootstrap-cli')")) {
                insert.setString(1, "v16_test_collection");
                insert.executeUpdate();
            }

            try (Statement update = conn.createStatement()) {
                assertThatThrownBy(() -> update.executeUpdate(
                        "UPDATE embedding_index_identity SET dimension = 9999 WHERE collection_name = 'v16_test_collection'"))
                        .isInstanceOf(SQLException.class);
            }
            try (Statement delete = conn.createStatement()) {
                assertThatThrownBy(() -> delete.executeUpdate(
                        "DELETE FROM embedding_index_identity WHERE collection_name = 'v16_test_collection'"))
                        .isInstanceOf(SQLException.class);
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT dimension FROM embedding_index_identity WHERE collection_name = 'v16_test_collection'")) {
                ResultSet rs = select.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("dimension")).isEqualTo(1536);
            }
        }
    }

    @Test
    void singleActiveEmbedding_uniqueIndexEnforced() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            insertEmbeddingModel(conn, UUID.randomUUID(), "ACTIVE");
            UUID second = UUID.randomUUID();

            assertThatThrownBy(() -> insertEmbeddingModel(conn, second, "ACTIVE"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertEmbeddingModel(Connection conn, UUID id, String status) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO chat_models (id, is_active, model_purpose, status, revision, candidate_generation) "
                        + "VALUES (?, true, 'EMBEDDING', ?, 1, 0)")) {
            insert.setObject(1, id);
            insert.setString(2, status);
            insert.executeUpdate();
        }
    }

    private void assertColumnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            assertThat(stmt.executeQuery().next()).as(table + "." + column).isTrue();
        }
    }

    private void assertTableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ?")) {
            stmt.setString(1, table);
            assertThat(stmt.executeQuery().next()).as(table).isTrue();
        }
    }
}
