package com.unisage.backend.service.messagetrace;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.internal.MessageTraceIngestRequest;
import com.unisage.backend.dto.response.internal.MessageTraceIngestResponse;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.MessageTraceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageTraceServiceImplTest {

    private final UUID messageId = UUID.randomUUID();

    private MessageTraceRepository messageTraceRepository;
    private MessageRepository messageRepository;
    private MessageTraceServiceImpl service;

    @BeforeEach
    void setUp() {
        messageTraceRepository = mock(MessageTraceRepository.class);
        messageRepository = mock(MessageRepository.class);
        service = new MessageTraceServiceImpl(messageTraceRepository, messageRepository, new ObjectMapper());
    }

    private void givenMessage(MsgRole role) {
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(Message.builder().id(messageId).role(role).content("x").build()));
    }

    private void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void ingest_upsertsTheTraceOfAnAssistantMessage() {
        givenMessage(MsgRole.ASSISTANT);

        MessageTraceIngestResponse response = service.ingest(new MessageTraceIngestRequest(
                messageId, "trace-1", List.of(Map.of("chunkId", "c1")), null));

        assertThat(response.chunkCount()).isEqualTo(1);
        assertThat(response.webSourceCount()).isZero();
        verify(messageTraceRepository).upsert(messageId, "trace-1", "[{\"chunkId\":\"c1\"}]", "[]");
    }

    @Test
    void ingest_rejectsAUserMessage() {
        givenMessage(MsgRole.USER);

        assertCode(() -> service.ingest(new MessageTraceIngestRequest(messageId, "t", List.of(), List.of())),
                ErrorCode.MESSAGE_NOT_FOUND);
        verify(messageTraceRepository, never()).upsert(any(), any(), any(), any());
    }

    @Test
    void ingest_rejectsATraceOverTheSizeLimit() {
        givenMessage(MsgRole.ASSISTANT);
        String content = "a".repeat(MessageTraceServiceImpl.MAX_TRACE_BYTES);

        assertCode(() -> service.ingest(new MessageTraceIngestRequest(
                        messageId, "t", List.of(Map.of("content", content)), List.of())),
                ErrorCode.MESSAGE_TRACE_INVALID);
        verify(messageTraceRepository, never()).upsert(any(), any(), any(), any());
    }
}
