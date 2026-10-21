package com.unisage.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.ManualBackupRequest;
import com.unisage.backend.dto.request.PostgresRestoreRequest;
import com.unisage.backend.dto.request.QdrantRestoreRequest;
import com.unisage.backend.dto.response.ApiResponse;
import com.unisage.backend.dto.response.BackupFileResponse;
import com.unisage.backend.dto.response.BackupOverviewResponse;
import com.unisage.backend.dto.response.BackupRunResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.PostgresRestoreStatusResponse;
import com.unisage.backend.dto.response.RestoreRunResponse;
import com.unisage.backend.entity.enums.BackupType;
import com.unisage.backend.service.backup.BackupAdminService;
import com.unisage.backend.service.backup.PostgresRestoreService;
import com.unisage.backend.service.backup.QdrantRestoreService;
import com.unisage.backend.utils.SecurityUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-102: the "Sao lưu & khôi phục" admin page (ADR-0010). Backups and restores run in the
 * background; the page polls {@code /overview}, and {@code /restores/postgres/status} while a
 * Postgres restore has the database down.
 */
@RestController
@RequestMapping("/admin/backups")
@RequiredArgsConstructor
public class BackupController {

    private final BackupAdminService backupAdminService;
    private final PostgresRestoreService postgresRestoreService;
    private final QdrantRestoreService qdrantRestoreService;
    private final SecurityUtil securityUtil;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<BackupOverviewResponse>> overview() {
        return ResponseEntity.ok(ApiResponse.success(backupAdminService.overview()));
    }

    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<PageResponse<List<BackupRunResponse>>>> runs(
            @RequestParam(required = false) BackupType type,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(backupAdminService.runs(type, pageable)));
    }

    @GetMapping("/runs/{id}/files")
    public ResponseEntity<ApiResponse<List<BackupFileResponse>>> files(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(backupAdminService.files(id)));
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<Void>> runNow(@Valid @RequestBody ManualBackupRequest request) {
        backupAdminService.runNow(request.type());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.successWithoutData());
    }

    @GetMapping("/restores")
    public ResponseEntity<ApiResponse<PageResponse<List<RestoreRunResponse>>>> restores(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(backupAdminService.restores(pageable)));
    }

    @PostMapping("/restores/postgres")
    public ResponseEntity<ApiResponse<RestoreRunResponse>> restorePostgres(
            @RequestBody PostgresRestoreRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(RestoreRunResponse.from(
                postgresRestoreService.start(request, securityUtil.getCurrentUserId(),
                        securityUtil.getCurrentUserCode()))));
    }

    @GetMapping("/restores/postgres/status")
    public ResponseEntity<ApiResponse<PostgresRestoreStatusResponse>> postgresRestoreStatus() {
        return ResponseEntity.ok(ApiResponse.success(backupAdminService.postgresRestoreStatus().orElse(null)));
    }

    @PostMapping("/restores/qdrant")
    public ResponseEntity<ApiResponse<RestoreRunResponse>> restoreQdrant(
            @Valid @RequestBody QdrantRestoreRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(RestoreRunResponse.from(
                qdrantRestoreService.start(request, securityUtil.getCurrentUserId(),
                        securityUtil.getCurrentUserCode()))));
    }
}
