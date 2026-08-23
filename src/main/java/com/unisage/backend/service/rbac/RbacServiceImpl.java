package com.unisage.backend.service.rbac;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.*;
import com.unisage.backend.dto.response.*;
import com.unisage.backend.entity.*;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.RolePermissionRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.service.rbac.RbacService;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RbacServiceImpl implements RbacService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    @Transactional
    public RoleResponse createRole(RoleRequest request) {
        if (roleRepository.findByName(request.name()).isPresent()) {
            throw new AppException(ErrorCode.ROLE_EXISTED);
        }

        Role role = Role.builder()
                .name(request.name())
                .isSystemRole(request.isSystemRole())
                .description(request.description())
                .isActive(request.isActive() != null ? request.isActive() : true)
                .build();
        role = roleRepository.save(role);

        if (request.permissionIds() != null && !request.permissionIds().isEmpty()) {
            for (UUID pId : request.permissionIds()) {
                assignPermissionToRole(role.getId(), pId);
            }
        }

        return getRoleDetails(role.getId());
    }

    @Override
    @Transactional
    public RoleResponse updateRole(UUID roleId, RoleRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        if (request.name() != null && !request.name().equals(role.getName())) {
            if (roleRepository.findByName(request.name()).isPresent()) {
                throw new AppException(ErrorCode.ROLE_EXISTED);
            }
            role.setName(request.name());
        }
        role.setIsSystemRole(request.isSystemRole());
        role.setDescription(request.description());
        if (request.isActive() != null) {
            role.setIsActive(request.isActive());
        }

        roleRepository.save(role);

        if (request.permissionIds() != null) {
            rolePermissionRepository.deleteByRoleId(roleId);

            for (UUID pId : request.permissionIds()) {
                assignPermissionToRole(roleId, pId);
            }
        }

        return getRoleDetails(roleId);
    }

    @Override
    @Transactional
    public void deleteRole(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        role.setIsActive(false);
        roleRepository.save(role);
    }

    @Override
    @Transactional
    public void recoverRole(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        role.setIsActive(true);
        roleRepository.save(role);
    }

    @Override
    @Transactional
    public void deleteResources(List<UUID> roleIds) {
        for (UUID id : roleIds) {
            deleteRole(id);
        }
    }

    @Override
    @Transactional
    public void recoverResources(List<UUID> roleIds) {
        for (UUID id : roleIds) {
            recoverRole(id);
        }
    }

    @Override
    public RoleResponse getRoleDetails(UUID roleId) {
        Role role = roleRepository.findById(roleId).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        List<RolePermission> mappings = rolePermissionRepository.findByRoleId(roleId);

        List<PermissionInfo> perms = mappings.stream()
                .map(rp -> PermissionInfo.builder()
                        .id(rp.getPermission().getId())
                        .name(rp.getPermission().getName())
                        .build())
                .collect(Collectors.toList());

        return mapToRoleResponse(role, perms);
    }

    @Override
    public PageResponse<List<RoleResponse>> getAllRoles(Pageable pageable) {
        Page<Role> rolePage = roleRepository.findAll(pageable);
        return PageResponse.fromPage(rolePage, role -> getRoleDetails(role.getId()));
    }

    @Override
    @Transactional
    public PermissionResponse createPermission(PermissionRequest request) {
        Permission permission = Permission.builder()
                .name(request.name())
                .isActive(request.isActive() != null ? request.isActive() : true)
                .build();
        return mapToPermissionResponse(permissionRepository.save(permission));
    }

    @Override
    @Transactional
    public PermissionResponse updatePermission(UUID permissionId, PermissionRequest request) {
        Permission p = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_FOUND));

        p.setName(request.name());
        if (request.isActive() != null) {
            p.setIsActive(request.isActive());
        }

        return mapToPermissionResponse(permissionRepository.save(p));
    }

    @Override
    @Transactional
    public void deletePermission(UUID permissionId) {
        Permission p = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_FOUND));
        p.setIsActive(false);
        permissionRepository.save(p);
    }

    @Override
    @Transactional
    public void recoverPermission(UUID permissionId) {
        Permission p = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_FOUND));
        p.setIsActive(true);
        permissionRepository.save(p);
    }

    @Override
    @Transactional
    public void deletePermissionResources(List<UUID> permissionIds) {
        for (UUID id : permissionIds) {
            deletePermission(id);
        }
    }

    @Override
    @Transactional
    public void recoverPermissionResources(List<UUID> permissionIds) {
        for (UUID id : permissionIds) {
            recoverPermission(id);
        }
    }

    @Override
    public PageResponse<List<PermissionResponse>> getAllPermissions(Pageable pageable) {
        Page<Permission> permissionPage = permissionRepository.findAll(pageable);
        return PageResponse.fromPage(permissionPage, this::mapToPermissionResponse);
    }

    @Override
    @Transactional
    public void assignPermissionToRole(UUID roleId, UUID permissionId) {
        Role role = roleRepository.findById(roleId).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        Permission perm = permissionRepository.findById(permissionId).orElseThrow(() -> new AppException(ErrorCode.PERMISSION_NOT_FOUND));

        RolePermission rp = RolePermission.builder()
                .role(role)
                .permission(perm)
                .build();
        rolePermissionRepository.save(rp);
    }

    private RoleResponse mapToRoleResponse(Role role, List<PermissionInfo> permissions) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .isSystemRole(role.getIsSystemRole())
                .description(role.getDescription())
                .permissions(permissions)
                .createdAt(role.getCreatedAt())
                .createdBy(role.getCreatedBy())
                .updatedAt(role.getUpdatedAt())
                .updatedBy(role.getUpdatedBy())
                .isActive(role.getIsActive())
                .build();
    }

    private PermissionResponse mapToPermissionResponse(Permission p) {
        return PermissionResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .createdAt(p.getCreatedAt())
                .createdBy(p.getCreatedBy())
                .updatedAt(p.getUpdatedAt())
                .updatedBy(p.getUpdatedBy())
                .isActive(p.getIsActive())
                .build();
    }
}
