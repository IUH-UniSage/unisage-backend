package com.unisage.backend.service.user;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.unisage.backend.dto.response.UserResponse;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class UserListFilterIntegrationTest extends PostgresIntegrationTest {

    private static final String TAG = "ulf-";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserService userService;

    private Role roleA;
    private Role roleB;

    @BeforeEach
    void setUp() {
        List<Role> roles = roleRepository.findAll();
        roleA = roles.get(0);
        roleB = roles.get(1);
        save("ULF001", "Đức", "Nguyễn Văn", UserStatus.ACTIVE, roleA);
        save("ULF002", "Lan", "Trần Thị", UserStatus.INACTIVE, roleA);
        save("ULG003", "Minh", "Lê", UserStatus.ACTIVE, roleB);
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(u -> u.getEmail().startsWith(TAG)).toList());
    }

    @Test
    void qMatchesCodeEmailAndFullNameIgnoringAccents() {
        // Full name in either order: "Nguyễn Văn Đức" and "Đức Nguyễn Văn".
        assertThat(codes("nguyen van duc", null, null)).containsExactly("ULF001");
        assertThat(codes("duc nguyen", null, null)).containsExactly("ULF001");
        assertThat(codes("ulf00", null, null)).containsExactlyInAnyOrder("ULF001", "ULF002");
        assertThat(codes("TRAN THI", null, null)).containsExactly("ULF002");
        assertThat(codes("ulg003@example", null, null)).containsExactly("ULG003");
    }

    @Test
    void filtersByStatusAndRole() {
        assertThat(codes(TAG, UserStatus.ACTIVE, roleA.getId())).containsExactly("ULF001");
        assertThat(codes(TAG, null, roleB.getId())).containsExactly("ULG003");
    }

    private List<String> codes(String q, UserStatus status, UUID roleId) {
        return userService.getAllUsers(q, status, roleId,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .data().stream().map(UserResponse::code).toList();
    }

    private void save(String code, String firstName, String lastName, UserStatus status, Role role) {
        userRepository.save(User.builder()
                .email(TAG + code.toLowerCase() + "@example.edu.vn")
                .passwordHash("x")
                .code(code)
                .firstName(firstName)
                .lastName(lastName)
                .status(status)
                .role(role)
                .build());
    }
}
