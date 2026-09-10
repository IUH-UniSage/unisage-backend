package com.unisage.backend.service.auth;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.response.AuthResponse;
import com.unisage.backend.entity.*;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.repository.UserDepartmentAccessRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceImplTest {

    private UserRepository userRepository;
    private UserDepartmentAccessRepository userDepartmentAccessRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthServiceImpl authService;

    private User user;
    private Role role;
    private Permission documentAll;
    private Permission documentCreate;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDepartmentAccessRepository = mock(UserDepartmentAccessRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        authService = new AuthServiceImpl(userRepository, userDepartmentAccessRepository, passwordEncoder, jwtUtil);

        documentAll = Permission.builder().id(UUID.randomUUID()).name("DOCUMENT_ALL").build();
        documentCreate = Permission.builder().id(UUID.randomUUID()).name("DOCUMENT_CREATE").build();

        role = Role.builder().id(UUID.randomUUID()).name("INGEST_ADMIN").isActive(true).build();
        role.setRolePermissions(List.of(
                RolePermission.builder().role(role).permission(documentAll).build(),
                RolePermission.builder().role(role).permission(documentCreate).build()
        ));

        user = User.builder()
                .id(UUID.randomUUID())
                .code("IA-001")
                .passwordHash("hashed")
                .firstName("Ingest")
                .lastName("Admin")
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();

        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(userRepository.findByCode("IA-001")).thenReturn(java.util.Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtUtil.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtUtil.getRefreshExpirationMs()).thenReturn(86_400_000L);
    }

    @Test
    void login_buildsDepartmentAccessAndPermissionsClaims() {
        Department department = Department.builder().id(UUID.randomUUID()).name("KHOA_CNTT").build();
        AccessLevel level3 = AccessLevel.builder().id(UUID.randomUUID()).level(3).build();
        UserDepartmentAccess access = UserDepartmentAccess.builder()
                .user(user)
                .department(department)
                .accessLevel(level3)
                .build();
        when(userDepartmentAccessRepository.findByUserId(user.getId())).thenReturn(List.of(access));

        AuthResponse response = authService.login(LoginRequest.builder().code("IA-001").password("pw").build());

        assertThat(response.accessToken()).isEqualTo("access-token");

        @SuppressWarnings("unchecked")
        var departmentAccessCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        var permissionsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateAccessToken(eq(user), departmentAccessCaptor.capture(), permissionsCaptor.capture());

        List<Map<String, Object>> departmentAccessClaim = departmentAccessCaptor.getValue();
        assertThat(departmentAccessClaim).hasSize(1);
        assertThat(departmentAccessClaim.get(0))
                .containsEntry("department_id", department.getId().toString())
                .containsEntry("access_level", 3);

        List<String> permissionsClaim = permissionsCaptor.getValue();
        assertThat(permissionsClaim).containsExactlyInAnyOrder("DOCUMENT_ALL", "DOCUMENT_CREATE");
    }

    @Test
    void login_superAdmin_emitsWildcardDepartmentAccessClaim() {
        Role superAdmin = Role.builder().id(UUID.randomUUID()).name("SUPER_ADMIN").isActive(true).build();
        superAdmin.setRolePermissions(List.of(
                RolePermission.builder().role(superAdmin).permission(documentAll).build()
        ));
        user.setRole(superAdmin);

        authService.login(LoginRequest.builder().code("IA-001").password("pw").build());

        @SuppressWarnings("unchecked")
        var departmentAccessCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateAccessToken(eq(user), departmentAccessCaptor.capture(), any());

        List<Map<String, Object>> departmentAccessClaim = departmentAccessCaptor.getValue();
        assertThat(departmentAccessClaim).hasSize(1);
        assertThat(departmentAccessClaim.get(0))
                .containsEntry("department_id", "*")
                .containsEntry("access_level", 100);
        // SUPER_ADMIN's grant rows are never queried — the wildcard short-circuits.
        verify(userDepartmentAccessRepository, never()).findByUserId(any());
    }

    @Test
    void login_departmentAccessWithNullLevel_isExcludedFromClaim() {
        Department department = Department.builder().id(UUID.randomUUID()).name("KHOA_KINH_TE").build();
        UserDepartmentAccess accessWithoutLevel = UserDepartmentAccess.builder()
                .user(user)
                .department(department)
                .accessLevel(null)
                .build();
        when(userDepartmentAccessRepository.findByUserId(user.getId())).thenReturn(List.of(accessWithoutLevel));

        authService.login(LoginRequest.builder().code("IA-001").password("pw").build());

        @SuppressWarnings("unchecked")
        var departmentAccessCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jwtUtil).generateAccessToken(eq(user), departmentAccessCaptor.capture(), any());

        List<Map<String, Object>> departmentAccessClaim = departmentAccessCaptor.getValue();
        assertThat(departmentAccessClaim).isEmpty();
    }
}
