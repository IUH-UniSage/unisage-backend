package com.unisage.backend.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.service.conversation.ConversationService;
import com.unisage.backend.service.systemconfig.SystemConfigResolver;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationRetentionJobTest {

    private ConversationService conversationService;
    private SystemConfigResolver configResolver;
    private ConversationRetentionJob job;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        configResolver = mock(SystemConfigResolver.class);
        job = new ConversationRetentionJob(conversationService, configResolver);
    }

    @Test
    void trimOldConversations_usesConfiguredLimit() {
        when(configResolver.getInt(eq(ConversationRetentionJob.MAX_CONVERSATIONS_KEY), anyInt())).thenReturn(2);

        job.trimOldConversations();

        verify(conversationService).trimToMaxPerOwner(2);
    }

    @Test
    void trimOldConversations_limitZero_meansUnlimited() {
        when(configResolver.getInt(eq(ConversationRetentionJob.MAX_CONVERSATIONS_KEY), anyInt())).thenReturn(0);

        job.trimOldConversations();

        verify(conversationService, never()).trimToMaxPerOwner(anyInt());
    }
}
