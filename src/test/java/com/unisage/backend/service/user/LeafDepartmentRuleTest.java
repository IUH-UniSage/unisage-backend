package com.unisage.backend.service.user;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.unisage.backend.entity.Department;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.DepartmentRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeafDepartmentRuleTest {

    private final Department khoa = Department.builder().id(UUID.randomUUID()).name("Khoa CNTT").build();
    private final Department boMon = Department.builder().id(UUID.randomUUID()).name("Bộ môn KTPM").parent(khoa).build();
    private final Department phong = Department.builder().id(UUID.randomUUID()).name("Phòng Đào tạo").build();

    private LeafDepartmentRule rule;

    @BeforeEach
    void setUp() {
        DepartmentRepository repository = mock(DepartmentRepository.class);
        when(repository.findAllByIsActive(true)).thenReturn(List.of(khoa, boMon, phong));
        rule = new LeafDepartmentRule(repository);
    }

    @Test
    void acceptsALeafAloneOrWithItsParent() {
        assertThatCode(() -> rule.check(List.of(boMon.getId()))).doesNotThrowAnyException();
        assertThatCode(() -> rule.check(List.of(khoa.getId(), boMon.getId()))).doesNotThrowAnyException();
        // A unit without child units is a leaf itself.
        assertThatCode(() -> rule.check(List.of(phong.getId()))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAParentWithoutAnyOfItsLeaves() {
        assertThatThrownBy(() -> rule.check(List.of(khoa.getId(), phong.getId())))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                            .isEqualTo(ErrorCode.USER_LEAF_DEPARTMENT_REQUIRED);
                    org.assertj.core.api.Assertions.assertThat(e.getErrors().get("departmentAccesses"))
                            .contains("Khoa CNTT");
                });
    }

    @Test
    void rejectsNoDepartments() {
        assertThatThrownBy(() -> rule.check(List.of())).isInstanceOf(AppException.class);
    }
}
