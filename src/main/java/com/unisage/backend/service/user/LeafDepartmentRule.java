package com.unisage.backend.service.user;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.unisage.backend.entity.Department;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DepartmentRepository;

import lombok.RequiredArgsConstructor;

/**
 * A user must sit in at least one leaf department (a unit with no active child units), and every
 * non-leaf department they are assigned to needs one of its leaves assigned too: "Khoa CNTT" alone
 * is not enough, "Khoa CNTT" + "Bộ môn KTPM" is.
 */
@Component
@RequiredArgsConstructor
public class LeafDepartmentRule {

    static final String FIELD = "departmentAccesses";

    private final DepartmentRepository departmentRepository;

    public void check(Collection<UUID> departmentIds) {
        Set<UUID> assigned = new HashSet<>(departmentIds);
        if (assigned.isEmpty()) {
            throw violation("Người dùng phải thuộc ít nhất một phòng ban cấp cuối (ví dụ một bộ môn trong khoa).");
        }

        Map<UUID, List<Department>> activeChildren = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (Department department : departmentRepository.findAllByIsActive(true)) {
            names.put(department.getId(), department.getName());
            if (department.getParent() != null) {
                activeChildren.computeIfAbsent(department.getParent().getId(), k -> new ArrayList<>()).add(department);
            }
        }

        List<String> missing = new ArrayList<>();
        for (UUID id : assigned) {
            if (activeChildren.containsKey(id) && !hasAssignedLeafBelow(id, assigned, activeChildren)) {
                missing.add(names.getOrDefault(id, id.toString()));
            }
        }
        if (!missing.isEmpty()) {
            missing.sort(String::compareTo);
            throw violation("Chọn thêm ít nhất một đơn vị trực thuộc cho: " + String.join(", ", missing) + ".");
        }
    }

    private boolean hasAssignedLeafBelow(UUID id, Set<UUID> assigned, Map<UUID, List<Department>> activeChildren) {
        for (Department child : activeChildren.getOrDefault(id, List.of())) {
            UUID childId = child.getId();
            boolean isLeaf = !activeChildren.containsKey(childId);
            if (assigned.contains(childId) && isLeaf) {
                return true;
            }
            if (hasAssignedLeafBelow(childId, assigned, activeChildren)) {
                return true;
            }
        }
        return false;
    }

    private static AppException violation(String message) {
        return new AppException(ErrorCode.USER_LEAF_DEPARTMENT_REQUIRED, Map.of(FIELD, message));
    }
}
