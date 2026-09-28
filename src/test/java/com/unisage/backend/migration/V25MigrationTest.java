package com.unisage.backend.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V25 creates request_usage_logs/request_usage_lines with the FK actions and CHECK constraints
 * plan.md "Data Model" requires. */
class V25MigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void tablesAndColumnsExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "request_usage_logs");
            assertTableExists(conn, "request_usage_lines");
            assertColumnExists(conn, "request_usage_logs", "request_id");
            assertColumnExists(conn, "request_usage_lines", "usage_log_id");
            assertColumnExists(conn, "request_usage_lines", "seq");
        }
    }

    @Test
    void requestIdIsUnique() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            UUID requestId = UUID.randomUUID();
            insertLog(conn, UUID.randomUUID(), requestId);

            assertThatThrownBy(() -> insertLog(conn, UUID.randomUUID(), requestId))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void usageLineSeqIsUniquePerLog() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            UUID logId = UUID.randomUUID();
            insertLog(conn, logId, UUID.randomUUID());
            insertLine(conn, UUID.randomUUID(), logId, 0);

            assertThatThrownBy(() -> insertLine(conn, UUID.randomUUID(), logId, 0))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void deletingUsageLogCascadesToLines() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            UUID logId = UUID.randomUUID();
            UUID lineId = UUID.randomUUID();
            insertLog(conn, logId, UUID.randomUUID());
            insertLine(conn, lineId, logId, 0);

            try (Statement delete = conn.createStatement()) {
                delete.executeUpdate("DELETE FROM request_usage_logs WHERE id = '" + logId + "'");
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT 1 FROM request_usage_lines WHERE id = ?")) {
                select.setObject(1, lineId);
                assertThat(select.executeQuery().next()).isFalse();
            }
        }
    }

    @Test
    void deletingChatModelNullsOutLineChatModelId() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            UUID chatModelId = UUID.randomUUID();
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO chat_models (id, is_active, model_purpose, status, revision, candidate_generation) "
                            + "VALUES (?, true, 'CHAT', 'ACTIVE', 1, 0)")) {
                insert.setObject(1, chatModelId);
                insert.executeUpdate();
            }

            UUID logId = UUID.randomUUID();
            UUID lineId = UUID.randomUUID();
            insertLog(conn, logId, UUID.randomUUID());
            insertLine(conn, lineId, logId, 0, chatModelId);

            try (Statement delete = conn.createStatement()) {
                delete.executeUpdate("DELETE FROM chat_models WHERE id = '" + chatModelId + "'");
            }

            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT chat_model_id FROM request_usage_lines WHERE id = ?")) {
                select.setObject(1, lineId);
                var rs = select.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("chat_model_id")).isNull();
            }
        }
    }

    @Test
    void costUsdMustMatchCostStatus() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            UUID logId = UUID.randomUUID();
            insertLog(conn, logId, UUID.randomUUID());

            assertThatThrownBy(() -> {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO request_usage_lines "
                                + "(id, usage_log_id, seq, node_name, attempt, estimated_cost_usd, cost_status, "
                                + "status, occurred_at, cost_usd) "
                                + "VALUES (?, ?, 0, 'n', 0, 0, 'UNPRICED', 'SUCCESS', now(), 1.0)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, logId);
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class);
        }
    }

    private void insertLog(Connection conn, UUID id, UUID requestId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO request_usage_logs (id, request_id, purpose, status, started_at) "
                        + "VALUES (?, ?, 'CHAT', 'SUCCESS', now())")) {
            insert.setObject(1, id);
            insert.setObject(2, requestId);
            insert.executeUpdate();
        }
    }

    private void insertLine(Connection conn, UUID id, UUID logId, int seq) throws SQLException {
        insertLine(conn, id, logId, seq, null);
    }

    private void insertLine(Connection conn, UUID id, UUID logId, int seq, UUID chatModelId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO request_usage_lines "
                        + "(id, usage_log_id, seq, node_name, attempt, chat_model_id, estimated_cost_usd, "
                        + "cost_status, status, occurred_at) "
                        + "VALUES (?, ?, ?, 'n', 0, ?, 0, 'FREE', 'SUCCESS', now())")) {
            insert.setObject(1, id);
            insert.setObject(2, logId);
            insert.setInt(3, seq);
            insert.setObject(4, chatModelId);
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
