package com.unisage.backend.service.userdepartmentaccess;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.UserDepartmentAccessRequest;
import com.unisage.backend.dto.response.UserDepartmentAccessResponse;

public interface UserDepartmentAccessService {

    UserDepartmentAccessResponse assign(UserDepartmentAccessRequest request);

    void revoke(UUID roleId, UUID departmentId);

    List<UserDepartmentAccessResponse> getByRole(UUID roleId);

    List<UserDepartmentAccessResponse> getByDepartment(UUID departmentId);
}
