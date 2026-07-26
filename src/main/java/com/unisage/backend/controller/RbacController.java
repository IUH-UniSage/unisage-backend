package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.PermissionResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.unisage.backend.dto.request.AssignPermissionRequest;
import com.unisage.backend.dto.request.PermissionRequest;
import com.unisage.backend.dto.request.RoleRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.RoleResponse;
import com.unisage.backend.service.rbac.RbacService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/rbac")
@RequiredArgsConstructor
public class RbacController {

    private final RbacService rbacService;

    // --- ROLES ---

    @PostMapping("/roles")
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(@RequestBody RoleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.createRole(request)));
    }

    @PutMapping("/roles/{id}")
    public ResponseEntity<ApiResponse<RoleResponse>> updateRole(@PathVariable UUID id, @RequestBody RoleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.updateRole(id, request)));
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable UUID id) {
        rbacService.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/roles/bulk")
    public ResponseEntity<ApiResponse<Void>> deleteResources(@RequestBody List<UUID> ids) {
        rbacService.deleteResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/roles/{id}/recover")
    public ResponseEntity<ApiResponse<Void>> recoverRole(@PathVariable UUID id) {
        rbacService.recoverRole(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/roles/bulk/recover")
    public ResponseEntity<ApiResponse<Void>> recoverRoles(@RequestBody List<UUID> ids) {
        rbacService.recoverResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/roles")
    public ResponseEntity<ApiResponse<PageResponse<List<RoleResponse>>>> getRoles(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.getAllRoles(pageable)));
    }

    @GetMapping("/roles/{id}")
    public ResponseEntity<ApiResponse<RoleResponse>> getRole(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.getRoleDetails(id)));
    }

    // --- PERMISSIONS ---

    @PostMapping("/permissions")
    public ResponseEntity<ApiResponse<PermissionResponse>> createPermission(@RequestBody PermissionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.createPermission(request)));
    }

    @PutMapping("/permissions/{id}")
    public ResponseEntity<ApiResponse<PermissionResponse>> updatePermission(@PathVariable UUID id, @RequestBody PermissionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.updatePermission(id, request)));
    }

    @DeleteMapping("/permissions/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePermission(@PathVariable UUID id) {
        rbacService.deletePermission(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/permissions/bulk")
    public ResponseEntity<ApiResponse<Void>> deletePermissions(@RequestBody List<UUID> ids) {
        rbacService.deletePermissionResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/permissions/{id}/recover")
    public ResponseEntity<ApiResponse<Void>> recoverPermission(@PathVariable UUID id) {
        rbacService.recoverPermission(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/permissions/bulk/recover")
    public ResponseEntity<ApiResponse<Void>> recoverPermissions(@RequestBody List<UUID> ids) {
        rbacService.recoverPermissionResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/permissions")
    public ResponseEntity<ApiResponse<PageResponse<List<PermissionResponse>>>> getPermissions(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(rbacService.getAllPermissions(pageable)));
    }

    // --- ASSIGNMENTS ---

    @PostMapping("/assign")
    public ResponseEntity<ApiResponse<Void>> assign(@RequestBody AssignPermissionRequest request) {
        rbacService.assignPermissionToRole(request.roleId(), request.permissionId());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}