package com.unisage.backend.service.userdepartmentaccess;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.UserDepartmentAccessRequest;
import com.unisage.backend.dto.response.UserDepartmentAccessResponse;
import com.unisage.backend.entity.Department;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.UserDepartmentAccess;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UserDepartmentAccessRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserDepartmentAccessServiceImpl implements UserDepartmentAccessService {

    private final UserDepartmentAccessRepository userDepartmentAccessRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public UserDepartmentAccessResponse assign(UserDepartmentAccessRequest request) {
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        Department department = departmentRepository.findById(request.departmentId())
                .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));

        if (userDepartmentAccessRepository.existsByRoleIdAndDepartmentId(request.roleId(), request.departmentId())) {
            throw new AppException(ErrorCode.USER_DEPARTMENT_ACCESS_EXISTED);
        }

        UserDepartmentAccess access = UserDepartmentAccess.builder()
                .role(role)
                .department(department)
                .accessLevel(request.accessLevel())
                .build();

        access = userDepartmentAccessRepository.save(access);
        return toResponse(access);
    }

    @Override
    @Transactional
    public void revoke(UUID roleId, UUID departmentId) {
        if (!userDepartmentAccessRepository.existsByRoleIdAndDepartmentId(roleId, departmentId)) {
            throw new AppException(ErrorCode.USER_DEPARTMENT_ACCESS_NOT_FOUND);
        }
        userDepartmentAccessRepository.deleteByRoleIdAndDepartmentId(roleId, departmentId);
    }

    @Override
    public List<UserDepartmentAccessResponse> getByRole(UUID roleId) {
        return userDepartmentAccessRepository.findByRoleId(roleId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDepartmentAccessResponse> getByDepartment(UUID departmentId) {
        return userDepartmentAccessRepository.findByDepartmentId(departmentId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private UserDepartmentAccessResponse toResponse(UserDepartmentAccess access) {
        return UserDepartmentAccessResponse.builder()
                .roleId(access.getRole().getId())
                .roleName(access.getRole().getName())
                .departmentId(access.getDepartment().getId())
                .departmentName(access.getDepartment().getName())
                .departmentPath(access.getDepartment().getPath())
                .accessLevel(access.getAccessLevel())
                .build();
    }
}
