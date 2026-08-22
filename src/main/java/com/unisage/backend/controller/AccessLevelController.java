package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.AccessLevelRequest;
import com.unisage.backend.dto.response.AccessLevelResponse;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.service.accesslevel.AccessLevelService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/access-levels")
@RequiredArgsConstructor
public class AccessLevelController {

    private final AccessLevelService accessLevelService;

    @PostMapping
    public ResponseEntity<ApiResponse<AccessLevelResponse>> createAccessLevel(
            @Valid @RequestBody AccessLevelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(accessLevelService.createAccessLevel(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccessLevelResponse>> getAccessLevel(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(accessLevelService.getAccessLevelById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AccessLevelResponse>>> getAllAccessLevels() {
        return ResponseEntity.ok(ApiResponse.success(accessLevelService.getAllAccessLevels()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AccessLevelResponse>> updateAccessLevel(
            @PathVariable UUID id, @Valid @RequestBody AccessLevelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(accessLevelService.updateAccessLevel(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAccessLevel(@PathVariable UUID id) {
        accessLevelService.deleteAccessLevel(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
