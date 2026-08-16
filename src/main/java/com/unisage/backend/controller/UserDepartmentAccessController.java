package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.UserDepartmentAccessRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.UserDepartmentAccessResponse;
import com.unisage.backend.service.userdepartmentaccess.UserDepartmentAccessService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/user-department-access")
@RequiredArgsConstructor
public class UserDepartmentAccessController {

    private final UserDepartmentAccessService userDepartmentAccessService;

    @PostMapping
    public ResponseEntity<ApiResponse<UserDepartmentAccessResponse>> assign(
            @Valid @RequestBody UserDepartmentAccessRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(userDepartmentAccessService.assign(request)));
    }

    @DeleteMapping("/{roleId}/{departmentId}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable UUID roleId, @PathVariable UUID departmentId) {
        userDepartmentAccessService.revoke(roleId, departmentId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/role/{roleId}")
    public ResponseEntity<ApiResponse<List<UserDepartmentAccessResponse>>> getByRole(@PathVariable UUID roleId) {
        return ResponseEntity.ok(ApiResponse.success(userDepartmentAccessService.getByRole(roleId)));
    }

    @GetMapping("/department/{departmentId}")
    public ResponseEntity<ApiResponse<List<UserDepartmentAccessResponse>>> getByDepartment(@PathVariable UUID departmentId) {
        return ResponseEntity.ok(ApiResponse.success(userDepartmentAccessService.getByDepartment(departmentId)));
    }
}
