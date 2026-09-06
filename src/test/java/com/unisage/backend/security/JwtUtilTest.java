package com.unisage.backend.security;

import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private User user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "Unisage_Test_Jwt_Signing_Secret_1234567890");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenExpiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshTokenExpiration", 86_400_000L);
        jwtUtil.init();

        Role role = Role.builder().id(UUID.randomUUID()).name("USER").build();
        user = User.builder()
                .id(UUID.randomUUID())
                .code("US-001")
                .role(role)
                .build();
    }

    @Test
    void generateAccessToken_roundTripsDepartmentAccessAndPermissions() {
        UUID departmentId = UUID.randomUUID();
        List<Map<String, Object>> departmentAccess = List.of(
                Map.of("department_id", departmentId.toString(), "access_level", 3)
        );
        List<String> permissions = List.of("DOCUMENT_ALL", "DOCUMENT_CREATE");

        String token = jwtUtil.generateAccessToken(user, departmentAccess, permissions);

        assertThat(jwtUtil.getUserId(token)).isEqualTo(user.getId().toString());
        assertThat(jwtUtil.getDepartmentAccess(token)).isEqualTo(departmentAccess);
        assertThat(jwtUtil.getPermissions(token)).isEqualTo(permissions);
    }

    @Test
    void generateAccessToken_emptyClaims_roundTripAsEmptyLists() {
        String token = jwtUtil.generateAccessToken(user, List.of(), List.of());

        assertThat(jwtUtil.getDepartmentAccess(token)).isEmpty();
        assertThat(jwtUtil.getPermissions(token)).isEmpty();
    }

    @Test
    void getDepartmentAccess_refreshTokenWithoutClaim_returnsNull() {
        String refreshToken = jwtUtil.generateRefreshToken(user);

        assertThat(jwtUtil.getDepartmentAccess(refreshToken)).isNull();
        assertThat(jwtUtil.getPermissions(refreshToken)).isNull();
    }
}
