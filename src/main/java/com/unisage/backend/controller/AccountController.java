package com.unisage.backend.controller;

import com.unisage.backend.dto.request.AccountRequest;
import com.unisage.backend.dto.request.ChangePasswordRequest;
import com.unisage.backend.dto.response.AccountResponse;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.service.account.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(@RequestBody AccountRequest request) {
        return ResponseEntity.ok(ApiResponse.success(accountService.createAccount(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> updateAccount(@PathVariable UUID id, @RequestBody AccountRequest request) {
        return ResponseEntity.ok(ApiResponse.success(accountService.updateAccount(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(@PathVariable UUID id) {
        accountService.deleteAccount(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/bulk")
    public ResponseEntity<ApiResponse<Void>> deleteAccounts(@RequestBody List<UUID> ids) {
        accountService.deleteResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{id}/recover")
    public ResponseEntity<ApiResponse<Void>> recoverAccount(@PathVariable UUID id) {
        accountService.recoverAccount(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/bulk/recover")
    public ResponseEntity<ApiResponse<Void>> recoverAccounts(@RequestBody List<UUID> ids) {
        accountService.recoverResources(ids);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<List<AccountResponse>>>> getAccounts(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(accountService.getAllAccounts(pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(accountService.getAccountDetails(id)));
    }

    @PatchMapping("/{id}/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@PathVariable UUID id, @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(id, request.password());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
