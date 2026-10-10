package com.unisage.backend.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.service.conversation.ConversationService;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces {@code maintenance.conversation.max_per_user}: once a day, every user and guest session keeps
 * only its N most recently updated conversations and the older ones are soft-deleted, the same
 * way a user deleting a conversation from the sidebar does. Creating a conversation is never
 * blocked by this limit (UNISAGE-94).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationRetentionJob {

    static final String MAX_CONVERSATIONS_KEY = "maintenance.conversation.max_per_user";

    private final ConversationService conversationService;
    private final SystemConfigResolver configResolver;

    @Scheduled(cron = "${app.conversation.retention.cron:0 30 3 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void trimOldConversations() {
        // Read live on every run, so an admin edit applies from the next run on.
        int keep = configResolver.getInt(MAX_CONVERSATIONS_KEY, 10);
        if (keep <= 0) {
            return;
        }
        int trimmed = conversationService.trimToMaxPerOwner(keep);
        if (trimmed > 0) {
            log.info("Soft-deleted {} conversation(s) beyond the newest {} per user", trimmed, keep);
        }
    }
}
