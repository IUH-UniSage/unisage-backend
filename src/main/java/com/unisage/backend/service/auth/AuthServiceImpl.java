package com.unisage.backend.service.auth;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.response.AuthResponse;
import com.unisage.backend.dto.response.PermissionInfo;
import com.unisage.backend.entity.Permission;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByCode(request.code())
                .orElseThrow(() -> new AppException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        if (!user.getIsActive()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!Boolean.TRUE.equals(user.getRole().getIsActive())) {
            throw new AppException(ErrorCode.USER_BANNED);
        }

        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        return buildSession(user);
    }

    @Override
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || !jwtUtil.validate(refreshToken)) {
            throw new AppException(ErrorCode.JWT_INVALID_TOKEN);
        }

        if (!jwtUtil.isRefreshToken(refreshToken)) {
            throw new AppException(ErrorCode.JWT_INVALID_TOKEN);
        }

        String userIdStr = jwtUtil.getUserId(refreshToken);
        if (userIdStr == null) {
            throw new AppException(ErrorCode.JWT_INVALID_TOKEN);
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (!user.getIsActive()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        return buildSession(user);
    }

    private AuthResponse buildSession(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .refreshTokenExpirationMs(jwtUtil.getRefreshExpirationMs())
                .email(user.getEmail())
                .code(user.getCode())
                .fullName(user.getFirstName() + " " + user.getLastName())
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole().getName())
                .isSystemRole(Boolean.TRUE.equals(user.getRole().getIsSystemRole()))
                .permissions(mapToPermissionInfo(user))
                .build();
    }

    private List<PermissionInfo> mapToPermissionInfo(User user) {
        return user.getRole().getRolePermissions().stream()
                .map(rp -> {
                    Permission p = rp.getPermission();
                    return PermissionInfo.builder()
                            .id(p.getId())
                            .name(p.getName())
                            .accessLevel(p.getAccessLevel())
                            .build();
                })
                .collect(Collectors.toList());
    }
}
