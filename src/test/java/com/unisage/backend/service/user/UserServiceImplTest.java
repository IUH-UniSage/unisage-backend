package com.unisage.backend.service.user;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.AccessLevelRepository;
import com.unisage.backend.repository.DepartmentRepository;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.UserDepartmentAccessRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.utils.SecurityUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private final UUID userId = UUID.randomUUID();
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        when(securityUtil.getCurrentUserId()).thenReturn(userId);
        service = new UserServiceImpl(userRepository, mock(RoleRepository.class),
                mock(PermissionRepository.class), passwordEncoder, mock(DepartmentRepository.class),
                mock(UserDepartmentAccessRepository.class), mock(AccessLevelRepository.class), securityUtil);

        user = User.builder().id(userId).passwordHash("old-hash").build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void changeMyPassword_updatesHashWhenCurrentPasswordMatches() {
        when(passwordEncoder.matches("current", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        service.changeMyPassword("current", "new-password");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
    }

    @Test
    void changeMyPassword_rejectsWrongCurrentPassword() {
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changeMyPassword("wrong", "new-password"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_INCORRECT));
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(userRepository, never()).save(any());
    }
}
