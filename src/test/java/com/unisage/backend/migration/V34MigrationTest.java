package com.unisage.backend.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V34 (UNISAGE-99): calculation_traces, per-item calculation tickets next to the per-message Report. */
class V34MigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GuestSessionRepository guestSessionRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;

    private UUID userId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        // Any user works for the FK; DataInitializer seeds the default accounts on startup.
        userId = userRepository.findAll().get(0).getId();
        GuestSession guestSession = guestSessionRepository.save(GuestSession.builder()
                .tokenHash(UUID.randomUUID().toString())
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build());
        Conversation conversation = conversationRepository.save(Conversation.builder()
                .guestSession(guestSession)
                .title("v34")
                .build());
        messageId = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("answer")
                .status(MsgStatus.COMPLETED)
                .build()).getId();
    }

    @Test
    void reportAndCalculationTicketsCoexist_andEachItemGetsItsOwnTicket() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertTicket(conn, "AI_UNANSWERED", null);
            insertTicket(conn, "AI_CALCULATION_WRONG", "T1");
            insertTicket(conn, "AI_CALCULATION_WRONG", "T2");

            assertThat(countTickets(conn)).isEqualTo(3);
        }
    }

    @Test
    void secondReportOnSameMessage_isStillRejected() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertTicket(conn, "AI_UNANSWERED", null);

            assertThatThrownBy(() -> insertTicket(conn, "OTHER", null)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void secondTicketForSameItem_isRejected() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertTicket(conn, "AI_CALCULATION_WRONG", "T1");

            assertThatThrownBy(() -> insertTicket(conn, "AI_CALCULATION_WRONG", "T1"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void calculationItemIsSetIfAndOnlyIfTypeIsCalculationWrong() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertTicket(conn, "AI_CALCULATION_WRONG", null))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertTicket(conn, "AI_UNANSWERED", "T1"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void tracesAreUniquePerMessageItem_andDeletedWithTheirMessage() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertTrace(conn, "T1");
            insertTrace(conn, "T2");
            assertThatThrownBy(() -> insertTrace(conn, "T1")).isInstanceOf(SQLException.class);

            try (PreparedStatement delete = conn.prepareStatement("DELETE FROM messages WHERE id = ?")) {
                delete.setObject(1, messageId);
                delete.executeUpdate();
            }
            try (PreparedStatement count = conn.prepareStatement(
                    "SELECT count(*) FROM calculation_traces WHERE message_id = ?")) {
                count.setObject(1, messageId);
                ResultSet rs = count.executeQuery();
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private void insertTicket(Connection conn, String type, String itemId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO tickets (id, is_active, description, status, title, type, message_id, user_id, "
                        + "calculation_item_id) VALUES (?, true, 'd', 'OPEN', 't', ?, ?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, type);
            insert.setObject(3, messageId);
            insert.setObject(4, userId);
            insert.setString(5, itemId);
            insert.executeUpdate();
        }
    }

    private int countTickets(Connection conn) throws SQLException {
        try (PreparedStatement count = conn.prepareStatement("SELECT count(*) FROM tickets WHERE message_id = ?")) {
            count.setObject(1, messageId);
            ResultSet rs = count.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private void insertTrace(Connection conn, String itemId) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO calculation_traces (id, message_id, item_id, run_id, trace) "
                        + "VALUES (?, ?, ?, 'run-1', '{}'::jsonb)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, messageId);
            insert.setString(3, itemId);
            insert.executeUpdate();
        }
    }
}
