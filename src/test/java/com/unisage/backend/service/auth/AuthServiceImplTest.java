package com.unisage.backend.service.auth;

import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void whenRefreshingWithAnInactiveRoleThenAccessIsRevoked() {
        UUID userId = UUID.randomUUID();
        User user = org.mockito.Mockito.mock(User.class);
        Role role = org.mockito.Mockito.mock(Role.class);

        when(jwtUtil.validate("refresh-token")).thenReturn(true);
        when(jwtUtil.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtUtil.getUserId("refresh-token")).thenReturn(userId.toString());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(user.getIsActive()).thenReturn(true);
        when(user.getRole()).thenReturn(role);
        when(role.getIsActive()).thenReturn(false);

        AppException error = assertThrows(
                AppException.class,
                () -> authService.refresh("refresh-token"));

        assertEquals(ErrorCode.USER_BANNED, error.getErrorCode());
    }
}
