package com.unisage.backend.controller;

import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.chatmodel.ChatModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/chat-models")
@RequiredArgsConstructor
public class ChatModelController {

    private final ChatModelService chatModelService;

    @PostMapping
    public ResponseEntity<ApiResponse<ChatModelResponse>> create(
            @Valid @RequestBody ChatModelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(chatModelService.create(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatModelResponse>> getById(
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<ChatModelResponse>>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.getAll(pageable)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatModelResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody ChatModelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        chatModelService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<ApiResponse<Void>> recover(@PathVariable UUID id) {
        chatModelService.recover(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
