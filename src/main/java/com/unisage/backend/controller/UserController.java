package com.unisage.backend.controller;

import com.unisage.backend.dto.request.ChangeMyPasswordRequest;
import com.unisage.backend.dto.request.ChangePasswordRequest;
import com.unisage.backend.dto.request.CreateUserRequest;
import jakarta.validation.Valid;
import com.unisage.backend.dto.request.UpdateUserRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UserResponse;
import com.unisage.backend.dto.response.UserDetailResponse;

import java.util.List;

import com.unisage.backend.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(userService.createUser(request)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe() {
        return ResponseEntity.ok(ApiResponse.success(userService.getMyProfile()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDetailResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUserById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<UserResponse>>>> getAll(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers(pageable)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserDetailResponse>> update(@PathVariable UUID id,
                                                                  @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateUser(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<ApiResponse<Void>> deleteUsers(@RequestBody List<UUID> ids) {
        userService.deleteResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<ApiResponse<Void>> recoverUser(@PathVariable UUID id) {
        userService.recoverUser(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/bulk/recover")
    public ResponseEntity<ApiResponse<Void>> recoverUsers(@RequestBody List<UUID> ids) {
        userService.recoverResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(@Valid @RequestBody ChangeMyPasswordRequest request) {
        userService.changeMyPassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{id}/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@PathVariable UUID id, @RequestBody ChangePasswordRequest request) {
        userService.changePassword(id, request.password());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
