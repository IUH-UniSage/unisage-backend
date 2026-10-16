package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.MessageTraceIngestRequest;
import com.unisage.backend.dto.response.internal.MessageTraceIngestResponse;
import com.unisage.backend.service.messagetrace.MessageTraceService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Staff-only retrieval traces pushed by unisage-agent (trace id + the chunks and web pages an
 * answer was generated from). Same security stack as every other {@code /internal/**} controller
 * (secret + CIDR + no-store).
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMessageTraceController {

    private final MessageTraceService messageTraceService;

    @PostMapping("/message-traces")
    public MessageTraceIngestResponse ingest(@Valid @RequestBody MessageTraceIngestRequest request) {
        return messageTraceService.ingest(request);
    }
}
