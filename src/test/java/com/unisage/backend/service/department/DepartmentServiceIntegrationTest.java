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
}
