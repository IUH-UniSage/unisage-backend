package com.unisage.backend.service.department;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.DepartmentRequest;
import com.unisage.backend.dto.response.DepartmentResponse;

public interface DepartmentService {

    DepartmentResponse createDepartment(DepartmentRequest request);

    void deleteDepartment(UUID id);

    void recoverDepartment(UUID id);

    DepartmentResponse getDepartmentById(UUID id);

    List<DepartmentResponse> getAllRoots(Boolean isActive);

    List<DepartmentResponse> getChildren(UUID parentId, Boolean isActive);
}
