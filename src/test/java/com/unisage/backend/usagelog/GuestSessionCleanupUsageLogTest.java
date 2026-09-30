package com.unisage.backend.usagelog;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.entity.Conversation;
import com.unisage.backend.entity.GuestSession;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.RequestUsageLog;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.MsgStatus;
import com.unisage.backend.entity.enums.UsagePurpose;
import com.unisage.backend.entity.enums.UsageRequestStatus;
import com.unisage.backend.repository.ConversationRepository;
import com.unisage.backend.repository.GuestSessionRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.RequestUsageLogRepository;
import com.unisage.backend.service.guestsession.GuestSessionService;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost Tracking plan.md Task 1 verification: guest cleanup ({@code GuestSessionServiceImpl
 * .purgeExpiredBatch}, a bulk JPQL delete of messages/conversations - not entity-level cascade)
 * must still leave the {@code request_usage_logs} row intact, with its message FKs nulled by the
 * DB's {@code ON DELETE SET NULL} - not deleted, since cost history must survive guest data purges.
 */
class GuestSessionCleanupUsageLogTest extends PostgresIntegrationTest {

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RequestUsageLogRepository requestUsageLogRepository;

    @Autowired
    private GuestSessionService guestSessionService;

    @Test
    void purgeExpiredBatch_leavesUsageLogWithMessageIdsNulled() {
        GuestSession guestSession = guestSessionRepository.save(GuestSession.builder()
                .tokenHash(UUID.randomUUID().toString())
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build());

        Conversation conversation = conversationRepository.save(Conversation.builder()
                .guestSession(guestSession)
                .title("expired guest conversation")
                .build());

        Message userMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.USER)
                .content("hello")
                .status(MsgStatus.COMPLETED)
                .build());

        Message assistantMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MsgRole.ASSISTANT)
                .content("hi")
                .status(MsgStatus.COMPLETED)
                .build());

        RequestUsageLog usageLog = requestUsageLogRepository.save(RequestUsageLog.builder()
                .requestId(UUID.randomUUID())
                .purpose(UsagePurpose.CHAT)
                .conversation(conversation)
                .userMessage(userMessage)
                .assistantMessage(assistantMessage)
                .status(UsageRequestStatus.SUCCESS)
                .startedAt(LocalDateTime.now())
                .finishedAt(LocalDateTime.now())
                .build());

        int purged = guestSessionService.purgeExpiredBatch(500);
        assertThat(purged).isEqualTo(1);

        RequestUsageLog reloaded = requestUsageLogRepository.findById(usageLog.getId()).orElseThrow();
        assertThat(reloaded.getUserMessage()).isNull();
        assertThat(reloaded.getAssistantMessage()).isNull();
        assertThat(reloaded.getConversation()).isNull();

        assertThat(conversationRepository.findById(conversation.getId())).isEmpty();
        assertThat(messageRepository.findById(userMessage.getId())).isEmpty();
    }
}
