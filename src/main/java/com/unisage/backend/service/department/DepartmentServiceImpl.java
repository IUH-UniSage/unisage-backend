package com.unisage.backend.service.department;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.DepartmentRequest;
import com.unisage.backend.dto.response.DepartmentNodeResponse;
import com.unisage.backend.dto.response.DepartmentResponse;
import com.unisage.backend.entity.Department;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DepartmentRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest request) {
        Department parent = null;

        if (request.parentId() != null) {
            parent = departmentRepository.findById(request.parentId())
                    .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));
        }

        Department department = Department.builder()
                .parent(parent)
                .name(request.name())
                .description(request.description())
                .build();

        department = departmentRepository.save(department);
        return toResponse(department);
    }

    @Override
    @Transactional
    public void deleteDepartment(UUID id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));
        recursiveDelete(department, LocalDateTime.now());
    }

    private void recursiveDelete(Department department, LocalDateTime now) {
        department.setIsActive(false);
        department.setDeletedAt(now);
        departmentRepository.save(department);

        List<Department> children = departmentRepository.findByParentIdAndIsActive(department.getId(), null);
        for (Department child : children) {
            recursiveDelete(child, now);
        }
    }

    @Override
    @Transactional
    public void recoverDepartment(UUID id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));

        department.setIsActive(true);
        department.setDeletedAt(null);
        departmentRepository.save(department);
    }

    @Override
    public DepartmentResponse getDepartmentById(UUID id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.DEPARTMENT_NOT_FOUND));
        return toResponse(department);
    }

    @Override
    public List<DepartmentResponse> getAllRoots(Boolean isActive) {
        List<Department> all = departmentRepository.findAllByIsActive(isActive);
        Map<UUID, List<Department>> childrenByParent = groupByParent(all);

        return all.stream()
                .filter(d -> d.getParent() == null)
                .map(d -> toResponseTree(d, childrenByParent))
                .collect(Collectors.toList());
    }

    @Override
    public List<DepartmentResponse> getChildren(UUID parentId, Boolean isActive) {
        List<Department> all = departmentRepository.findAllByIsActive(isActive);
        Map<UUID, List<Department>> childrenByParent = groupByParent(all);

        List<Department> directChildren = childrenByParent.getOrDefault(parentId, List.of());
        return directChildren.stream()
                .map(d -> toResponseTree(d, childrenByParent))
                .collect(Collectors.toList());
    }

    private Map<UUID, List<Department>> groupByParent(List<Department> all) {
        return all.stream()
                .filter(d -> d.getParent() != null)
                .collect(Collectors.groupingBy(d -> d.getParent().getId()));
    }

    private DepartmentResponse toResponseTree(Department department, Map<UUID, List<Department>> childrenByParent) {
        List<Department> children = childrenByParent.getOrDefault(department.getId(), List.of());
        List<DepartmentNodeResponse> childNodes = children.stream()
                .map(child -> toNode(child, childrenByParent))
                .collect(Collectors.toList());

        return DepartmentResponse.builder()
                .id(department.getId())
                .name(department.getName())
                .description(department.getDescription())
                .parentId(department.getParent() != null ? department.getParent().getId() : null)
                .isActive(department.getIsActive())
                .deletedAt(department.getDeletedAt())
                .children(childNodes)
                .createdBy(department.getCreatedBy() != null ? department.getCreatedBy().getId().toString() : null)
                .createdByName(department.getCreatedBy() != null ? department.getCreatedBy().getFullName() : null)
                .createdAt(department.getCreatedAt())
                .updatedBy(department.getUpdatedBy() != null ? department.getUpdatedBy().getId().toString() : null)
                .updatedByName(department.getUpdatedBy() != null ? department.getUpdatedBy().getFullName() : null)
                .updatedAt(department.getUpdatedAt())
                .build();
    }

    private DepartmentNodeResponse toNode(Department department, Map<UUID, List<Department>> childrenByParent) {
        List<Department> children = childrenByParent.getOrDefault(department.getId(), List.of());
        List<DepartmentNodeResponse> childNodes = children.stream()
                .map(child -> toNode(child, childrenByParent))
                .collect(Collectors.toList());

        return DepartmentNodeResponse.builder()
                .id(department.getId())
                .name(department.getName())
                .description(department.getDescription())
                .parentId(department.getParent() != null ? department.getParent().getId() : null)
                .isActive(department.getIsActive())
                .deletedAt(department.getDeletedAt())
                .children(childNodes)
                .build();
    }

    private DepartmentResponse toResponse(Department department) {
        List<Department> children = departmentRepository.findByParentId(department.getId());
        List<DepartmentNodeResponse> childNodes = children.stream()
                .map(this::toNode)
                .collect(Collectors.toList());

        return DepartmentResponse.builder()
                .id(department.getId())
                .name(department.getName())
                .description(department.getDescription())
                .parentId(department.getParent() != null ? department.getParent().getId() : null)
                .isActive(department.getIsActive())
                .deletedAt(department.getDeletedAt())
                .children(childNodes)
                .createdBy(department.getCreatedBy() != null ? department.getCreatedBy().getId().toString() : null)
                .createdByName(department.getCreatedBy() != null ? department.getCreatedBy().getFullName() : null)
                .createdAt(department.getCreatedAt())
                .updatedBy(department.getUpdatedBy() != null ? department.getUpdatedBy().getId().toString() : null)
                .updatedByName(department.getUpdatedBy() != null ? department.getUpdatedBy().getFullName() : null)
                .updatedAt(department.getUpdatedAt())
                .build();
    }

    private DepartmentNodeResponse toNode(Department department) {
        List<Department> children = departmentRepository.findByParentId(department.getId());
        List<DepartmentNodeResponse> childNodes = children.stream()
                .map(this::toNode)
                .collect(Collectors.toList());

        return DepartmentNodeResponse.builder()
                .id(department.getId())
                .name(department.getName())
                .description(department.getDescription())
                .parentId(department.getParent() != null ? department.getParent().getId() : null)
                .isActive(department.getIsActive())
                .deletedAt(department.getDeletedAt())
                .children(childNodes)
                .build();
    }
}
