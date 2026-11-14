package com.unisage.backend.service.department;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.unisage.backend.dto.request.DepartmentRequest;
import com.unisage.backend.dto.response.DepartmentResponse;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepartmentServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private DepartmentRepository departmentRepository;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Children first: parent_id is a foreign key.
        for (int i = created.size() - 1; i >= 0; i--) {
            departmentRepository.deleteById(created.get(i));
        }
        created.clear();
    }

    private UUID create(String name, UUID parentId) {
        DepartmentResponse response = departmentService.createDepartment(new DepartmentRequest(name, null, parentId));
        created.add(response.id());
        return response.id();
    }

    @Test
    void updateRenamesWithFreeTextAndRejectsALoop() {
        UUID khoa = create("Khoa CNTT", null);
        UUID boMon = create("Bộ môn KTPM", khoa);

        DepartmentResponse renamed = departmentService.updateDepartment(
                khoa, new DepartmentRequest("  Khoa Công nghệ thông tin ", "mô tả", null));
        assertThat(renamed.name()).isEqualTo("Khoa Công nghệ thông tin");
        assertThat(renamed.description()).isEqualTo("mô tả");

        assertThatThrownBy(() -> departmentService.updateDepartment(khoa, new DepartmentRequest("X", null, boMon)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.DEPARTMENT_PARENT_INVALID);
        assertThatThrownBy(() -> departmentService.updateDepartment(khoa, new DepartmentRequest("X", null, khoa)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void siblingsAreOrderedByCreationAtEveryLevel() {
        UUID root = create("ZZ Gốc", null);
        UUID b = create("B lớp 1", root);
        UUID a = create("A lớp 1", root);
        UUID b2 = create("Y lớp 2", b);
        UUID b1 = create("X lớp 2", b);

        DepartmentResponse tree = departmentService.getAllRoots(null).stream()
                .filter(d -> d.id().equals(root)).findFirst().orElseThrow();

        assertThat(tree.children()).extracting(c -> c.id()).containsExactly(b, a);
        assertThat(tree.children().get(0).children()).extracting(c -> c.id()).containsExactly(b2, b1);
        assertThat(departmentService.getChildren(root, null)).extracting(DepartmentResponse::id)
                .containsExactly(b, a);
    }

    @Test
    void filteringByStatusKeepsNodesWhoseParentIsFilteredOut() {
        UUID khoa = create("Khoa lọc", null);
        UUID boMon = create("Bộ môn lọc", khoa);
        departmentService.deleteDepartment(boMon);

        assertThat(departmentService.getAllRoots(false)).extracting(DepartmentResponse::id).contains(boMon);
        assertThat(departmentService.getAllRoots(true)).extracting(DepartmentResponse::id)
                .contains(khoa).doesNotContain(boMon);
    }

    @Test
    void recoverRestoresChildrenOnlyWhenAsked() {
        UUID khoa = create("Khoa khôi phục", null);
        UUID boMon = create("Bộ môn khôi phục", khoa);
        UUID nhom = create("Nhóm khôi phục", boMon);

        departmentService.deleteDepartment(khoa);
        departmentService.recoverDepartment(khoa, false);
        assertThat(isActive(khoa)).isTrue();
        assertThat(isActive(boMon)).isFalse();

        departmentService.deleteDepartment(khoa);
        departmentService.recoverDepartment(khoa, true);
        assertThat(List.of(isActive(khoa), isActive(boMon), isActive(nhom))).containsOnly(true);
    }

    private boolean isActive(UUID id) {
        return departmentRepository.findById(id).orElseThrow().getIsActive();
    }

    @Test
    void bulkDeleteAndRecoverWorkOnSeveralSubtrees() {
        UUID a = create("Khoa A bulk", null);
        UUID a1 = create("Bộ môn A1 bulk", a);
        UUID b = create("Khoa B bulk", null);

        departmentService.deleteDepartments(List.of(a, b));
        assertThat(List.of(isActive(a), isActive(a1), isActive(b))).containsOnly(false);

        departmentService.recoverDepartments(List.of(a, b), true);
        assertThat(List.of(isActive(a), isActive(a1), isActive(b))).containsOnly(true);

        assertThatThrownBy(() -> departmentService.deleteDepartments(List.of(a, UUID.randomUUID())))
                .isInstanceOf(AppException.class);
        assertThat(isActive(a)).isTrue();
    }
}
