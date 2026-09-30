package com.unisage.backend.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V26 (budgets/budget_alert_settings/budget_alert_log) and V27 (permission seed) - plan.md
 * "Budget", "BudgetAlertSetting", "BudgetAlertLog". */
class V26MigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void tablesExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "budgets");
            assertTableExists(conn, "budget_alert_settings");
            assertTableExists(conn, "budget_alert_log");
        }
    }

    @Test
    void budgetAlertSettingsIsSeededSingleton() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT thresholds_percent, in_app_enabled FROM budget_alert_settings WHERE id = 1")) {
                var rs = select.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("in_app_enabled")).isTrue();
            }
        }
    }

    @Test
    void budgetAlertSettingsRejectsSecondRow() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO budget_alert_settings (id) VALUES (2)")) {
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void budgetsRejectsSystemScopeWithProviderSet() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            assertThatThrownBy(() -> insertBudget(conn, UUID.randomUUID(), "SYSTEM", "openai", null,
                    "MONTHLY", "ALERT", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void budgetsRejectsThrottleWithoutConcurrency() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            assertThatThrownBy(() -> insertBudget(conn, UUID.randomUUID(), "SYSTEM", null, null,
                    "MONTHLY", "THROTTLE", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void budgetsUniqueIndexBlocksSecondEnabledSystemBudgetForSamePeriod() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            insertBudget(conn, UUID.randomUUID(), "SYSTEM", null, null, "MONTHLY", "ALERT", null);

            assertThatThrownBy(() -> insertBudget(conn, UUID.randomUUID(), "SYSTEM", null, null,
                    "MONTHLY", "ALERT", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void budgetsUniqueIndexIsCaseInsensitiveForProvider() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            insertBudget(conn, UUID.randomUUID(), "PROVIDER", "OpenAI", null, "MONTHLY", "ALERT", null);

            assertThatThrownBy(() -> insertBudget(conn, UUID.randomUUID(), "PROVIDER", "openai", null,
                    "MONTHLY", "ALERT", null))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertBudget(Connection conn, UUID id, String scope, String scopeProvider, String scopePurpose,
            String period, String action, Integer throttle) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO budgets (id, is_active, scope, scope_provider, scope_purpose, period, "
                        + "limit_usd, action, throttle_max_concurrency, is_enabled) "
                        + "VALUES (?, true, ?, ?, ?, ?, ?, ?, ?, true)")) {
            insert.setObject(1, id);
            insert.setString(2, scope);
            insert.setString(3, scopeProvider);
            insert.setString(4, scopePurpose);
            insert.setString(5, period);
            insert.setBigDecimal(6, BigDecimal.TEN);
            insert.setString(7, action);
            insert.setObject(8, throttle);
            insert.executeUpdate();
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
