package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.SendMessageRequest;
import com.unisage.backend.dto.request.UpdateMessageRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.MessageResponse;
import com.unisage.backend.service.conversation.MessageService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> send(@Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(messageService.send(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMessageRequest request) {
        return ResponseEntity.ok(ApiResponse.success(messageService.update(id, request)));
    }

    @GetMapping("/conversation/{conversationId}")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getByConversation(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.success(messageService.getByConversation(conversationId, limit)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MessageResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(messageService.getById(id)));
    }
}
