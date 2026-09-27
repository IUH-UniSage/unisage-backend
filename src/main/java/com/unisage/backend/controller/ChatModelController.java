package com.unisage.backend.controller;

import com.unisage.backend.dto.request.ChatModelPriorityUpdateRequest;
import com.unisage.backend.dto.request.ChatModelRequest;
import com.unisage.backend.dto.request.ChatModelStatusUpdateRequest;
import com.unisage.backend.dto.request.ChatModelUpdateRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.ChatModelResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.enums.ChatModelPurpose;
import com.unisage.backend.entity.enums.ChatModelStatus;
import com.unisage.backend.service.chatmodel.ChatModelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

    /**
     * {@code isActive} has no server-side default: omitting it returns every row regardless of
     * soft-delete state. The admin UI defaults to {@code isActive=true} itself (so a fresh page
     * load only shows non-deleted rows) but always sends the param explicitly, which is what lets
     * its own "Tất cả" filter option ask for both by omitting it — a server-side default here would
     * make that option indistinguishable from not passing the param at all.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<ChatModelResponse>>>> getAll(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ChatModelPurpose modelPurpose,
            @RequestParam(required = false) ChatModelStatus status,
            @RequestParam(required = false) Boolean isActive,
            @PageableDefault(size = 10, sort = "priority") Pageable pageable) {
        return ResponseEntity.ok(
                ApiResponse.success(chatModelService.getAll(q, modelPurpose, status, isActive, pageable)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ChatModelResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody ChatModelUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.update(id, request)));
    }

    /** plan.md "State machine" — only ACTIVE/INACTIVE are SA-triggerable; every other transition happens via verify/promote. */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ChatModelResponse>> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody ChatModelStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.updateStatus(id, request.status())));
    }

    @PatchMapping("/{id}/priority")
    public ResponseEntity<ApiResponse<ChatModelResponse>> updatePriority(
            @PathVariable UUID id, @Valid @RequestBody ChatModelPriorityUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.updatePriority(id, request.priority())));
    }

    /** Creates a new verification job for the row's current candidate; supersedes any job still in flight. */
    @PostMapping("/{id}/verify")
    public ResponseEntity<ApiResponse<ChatModelResponse>> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(chatModelService.verify(id)));
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
