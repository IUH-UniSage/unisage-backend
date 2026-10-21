package com.unisage.backend.service.messagetrace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.internal.MessageTraceIngestRequest;
import com.unisage.backend.dto.response.internal.MessageTraceIngestResponse;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.MessageTraceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MessageTraceServiceImpl implements MessageTraceService {

    static final int MAX_TRACE_BYTES = 256 * 1024;

    private final MessageTraceRepository messageTraceRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public MessageTraceIngestResponse ingest(MessageTraceIngestRequest request) {
        List<Map<String, Object>> chunks = request.chunks() != null ? request.chunks() : List.of();
        List<Map<String, Object>> webSources = request.webSources() != null ? request.webSources() : List.of();
        String chunksJson = serialize(chunks);
        String webSourcesJson = serialize(webSources);
        int bytes = chunksJson.getBytes(StandardCharsets.UTF_8).length
                + webSourcesJson.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TRACE_BYTES) {
            throw new AppException(ErrorCode.MESSAGE_TRACE_INVALID);
        }

        Message message = messageRepository.findById(request.messageId())
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));
        if (message.getRole() != MsgRole.ASSISTANT) {
            throw new AppException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        messageTraceRepository.upsert(message.getId(), request.traceId(), chunksJson, webSourcesJson);

        return MessageTraceIngestResponse.builder()
                .messageId(message.getId())
                .traceId(request.traceId())
                .chunkCount(chunks.size())
                .webSourceCount(webSources.size())
                .build();
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.MESSAGE_TRACE_INVALID);
        }
    }
}
