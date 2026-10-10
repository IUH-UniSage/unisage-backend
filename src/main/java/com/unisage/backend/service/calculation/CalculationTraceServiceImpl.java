package com.unisage.backend.service.calculation;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.request.internal.CalculationTraceIngestRequest;
import com.unisage.backend.dto.response.internal.CalculationTraceIngestResponse;
import com.unisage.backend.entity.Message;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.CalculationTraceRepository;
import com.unisage.backend.repository.MessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CalculationTraceServiceImpl implements CalculationTraceService {

    static final int MAX_TRACE_BYTES = 16 * 1024;

    private final CalculationTraceRepository calculationTraceRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CalculationTraceIngestResponse ingest(CalculationTraceIngestRequest request) {
        Set<String> seen = new HashSet<>();
        List<String> traceJson = request.items().stream().map(item -> {
            if (!seen.add(item.itemId())) {
                throw new AppException(ErrorCode.CALCULATION_TRACE_INVALID);
            }
            return serialize(item.trace());
        }).toList();

        Message message = messageRepository.findById(request.messageId())
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));
        if (message.getRole() != MsgRole.ASSISTANT) {
            throw new AppException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        for (int i = 0; i < request.items().size(); i++) {
            CalculationTraceIngestRequest.Item item = request.items().get(i);
            calculationTraceRepository.upsert(
                    UUID.randomUUID(), message.getId(), item.itemId(), item.runId(), traceJson.get(i));
        }

        return CalculationTraceIngestResponse.builder()
                .messageId(message.getId())
                .itemIds(request.items().stream().map(CalculationTraceIngestRequest.Item::itemId).toList())
                .build();
    }

    private String serialize(Object trace) {
        try {
            String json = objectMapper.writeValueAsString(trace);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_TRACE_BYTES) {
                throw new AppException(ErrorCode.CALCULATION_TRACE_INVALID);
            }
            return json;
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.CALCULATION_TRACE_INVALID);
        }
    }
}
