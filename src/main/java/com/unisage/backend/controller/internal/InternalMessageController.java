package com.unisage.backend.controller.internal;

import java.util.UUID;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.CancelClarificationRequest;
import com.unisage.backend.dto.response.internal.ClarificationStatusResponse;
import com.unisage.backend.service.conversation.MessageService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Message writes that only unisage-agent may make - SPEC-clarification-panel §7.2. Sits under
 * {@code /internal/**}, so {@code InternalSecretFilter}/{@code InternalCallerCidrFilter} guard it and
 * the gateway never routes it from outside.
 */
@RestController
@RequestMapping("/internal/messages")
@RequiredArgsConstructor
public class InternalMessageController {

    private final MessageService messageService;

    /** Cancel an open clarification panel: {@code open -> cancelled}; repeating it is a no-op. */
    @PatchMapping("/{id}/clarification")
    public ClarificationStatusResponse cancelClarification(
            @PathVariable UUID id,
            @Valid @RequestBody CancelClarificationRequest request) {
        return messageService.cancelClarification(id, request);
    }
}
