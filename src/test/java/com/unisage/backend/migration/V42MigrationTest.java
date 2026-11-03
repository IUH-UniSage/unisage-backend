package com.unisage.backend.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V42 (UNISAGE-107): conversation_shares + conversations.source_share_id. The context starting at
 * all already proves {@code ddl-auto=validate} accepts the new entity mappings.
 */
class V42MigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;

    private UUID userId;
    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void setUp() {
        User user = userRepository.findAll().get(0);
        userId = user.getId();
        Conversation conversation = conversationRepository.save(Conversation.builder().user(user).title("v42").build());
        conversationId = conversation.getId();
        messageId = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("answer")
                .status(MsgStatus.COMPLETED)
                .build()).getId();
    }

    @Test
    void tokenIsUnique() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String token = "tok-" + UUID.randomUUID();
            insertShare(conn, UUID.randomUUID(), token);

            assertThatThrownBy(() -> insertShare(conn, UUID.randomUUID(), token)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void oneLiveCopyPerShareAndUser_butADeletedCopyFreesTheSlot() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID shareId = UUID.randomUUID();
            insertShare(conn, shareId, "tok-" + UUID.randomUUID());
            UUID first = insertCopy(conn, shareId, null);

            assertThatThrownBy(() -> insertCopy(conn, shareId, null)).isInstanceOf(SQLException.class);

            try (PreparedStatement delete = conn.prepareStatement(
                    "UPDATE conversations SET deleted_at = now() WHERE id = ?")) {
                delete.setObject(1, first);
                delete.executeUpdate();
            }
            assertThatCode(() -> insertCopy(conn, shareId, null)).doesNotThrowAnyException();
            // Copies of the same link by different users don't collide.
            UUID otherUser = userRepository.findAll().stream().map(User::getId)
                    .filter(id -> !id.equals(userId)).findFirst().orElseThrow();
            assertThatCode(() -> insertCopy(conn, shareId, otherUser)).doesNotThrowAnyException();
        }
    }

    @Test
    void sourceShareMustExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            assertThatThrownBy(() -> insertCopy(conn, UUID.randomUUID(), null)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void cutoffMessageAndConversationMustExist() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            UUID realMessage = messageId;
            messageId = UUID.randomUUID();
            assertThatThrownBy(() -> insertShare(conn, UUID.randomUUID(), "tok-" + UUID.randomUUID()))
                    .isInstanceOf(SQLException.class);
            messageId = realMessage;
            conversationId = UUID.randomUUID();
            assertThatThrownBy(() -> insertShare(conn, UUID.randomUUID(), "tok-" + UUID.randomUUID()))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertShare(Connection conn, UUID id, String token) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO conversation_shares (id, is_active, token, conversation_id, owner_id, title, "
                        + "cutoff_message_id, cutoff_at, message_count) VALUES (?, true, ?, ?, ?, 't', ?, ?, 1)")) {
            insert.setObject(1, id);
            insert.setString(2, token);
            insert.setObject(3, conversationId);
            insert.setObject(4, userId);
            insert.setObject(5, messageId);
            insert.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            insert.executeUpdate();
        }
    }

    private UUID insertCopy(Connection conn, UUID shareId, UUID ownerOrNull) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO conversations (id, is_active, title, user_id, source_share_id) VALUES (?, true, 'c', ?, ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, ownerOrNull != null ? ownerOrNull : userId);
            insert.setObject(3, shareId);
            insert.executeUpdate();
        }
        return id;
    }
}
