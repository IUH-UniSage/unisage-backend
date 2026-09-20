package com.unisage.backend.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.UpdateSystemConfigRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.SystemConfigResponse;
import com.unisage.backend.entity.enums.SystemConfigCategory;
import com.unisage.backend.service.systemconfig.SystemConfigService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-64: read + update known {@code system_configs} rows. Deliberately GET/PUT only — new
 * keys are added via Flyway migration, not created/deleted through this API.
 */
@RestController
@RequestMapping("/system-configs")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SystemConfigResponse>>> getAll(
            @RequestParam(required = false) SystemConfigCategory category) {
        return ResponseEntity.ok(ApiResponse.success(systemConfigService.getAll(category)));
    }

    @GetMapping("/{configKey}")
    public ResponseEntity<ApiResponse<SystemConfigResponse>> getByKey(@PathVariable String configKey) {
        return ResponseEntity.ok(ApiResponse.success(systemConfigService.getByKey(configKey)));
    }

    @PutMapping("/{configKey}")
    public ResponseEntity<ApiResponse<SystemConfigResponse>> updateValue(
            @PathVariable String configKey, @Valid @RequestBody UpdateSystemConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success(systemConfigService.updateValue(configKey, request)));
    }
}
