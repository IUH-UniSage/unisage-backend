package com.unisage.backend.service.auth;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.request.SelectProfileRequest;
import com.unisage.backend.dto.response.AuthResponse;
import com.unisage.backend.dto.response.PermissionInfo;
import com.unisage.backend.dto.response.SelectProfileResponse;
import com.unisage.backend.dto.response.UserProfileResponse;
import com.unisage.backend.entity.Account;
import com.unisage.backend.entity.Permission;
import com.unisage.backend.entity.User;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.repository.AccountRepository;
import com.unisage.backend.security.JwtUtil;
import com.unisage.backend.service.auth.AuthService;
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
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Account account = accountRepository.findByCode(request.code())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (!account.getIsActive()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new AppException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        List<User> users = account.getUsers();
        if (users.isEmpty()) {
            throw new AppException(ErrorCode.PROFILE_NOT_FOUND);
        }

        account.setLastLogin(LocalDateTime.now());
        accountRepository.save(account);

        List<UserProfileResponse> profiles = users.stream()
                .filter(u -> u.getRole().getIsActive())
                .map(u -> UserProfileResponse.builder()
                        .userId(u.getId())
                        .fullName(u.getFirstName() + " " + u.getLastName())
                        .avatarUrl(u.getAvatarUrl())
                        .role(u.getRole().getName())
                        .roleDescription(u.getRole().getDescription())
                        .isSystemRole(Boolean.TRUE.equals(u.getRole().getIsSystemRole()))
                        .permissions(mapToPermissionInfo(u))
                        .build())
                .collect(Collectors.toList());

        String accessToken = null;
        String refreshToken = null;
        long refreshTokenExpirationMs = 0;

        if (users.size() == 1) {
            User user = users.get(0);
            if (!user.getRole().getIsActive()) {
                throw new AppException(ErrorCode.USER_BANNED);
            }
            AuthResponse session = buildSession(user, account);
            accessToken = session.accessToken();
            refreshToken = session.refreshToken();
            refreshTokenExpirationMs = session.refreshTokenExpirationMs();
        }

        return AuthResponse.builder()
                .profiles(profiles)
                .email(account.getEmail())
                .code(account.getCode())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .refreshTokenExpirationMs(refreshTokenExpirationMs)
                .build();
    }

    @Override
    @Transactional
    public SelectProfileResponse selectProfile(SelectProfileRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new AppException(ErrorCode.PROFILE_NOT_FOUND));
        if (!user.getRole().getIsActive()) {
            throw new AppException(ErrorCode.USER_BANNED);
        }
        Account account = user.getAccount();

        if (!account.getIsActive()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return SelectProfileResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .refreshTokenExpirationMs(jwtUtil.getRefreshExpirationMs())
                .email(account.getEmail())
                .fullName(user.getFirstName() + " " + user.getLastName())
                .role(user.getRole().getName())
                .code(account.getCode())
                .avatarUrl(user.getAvatarUrl())
                .isSystemRole(Boolean.TRUE.equals(user.getRole().getIsSystemRole()))
                .permissions(mapToPermissionInfo(user))
                .build();
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
        Account account = user.getAccount();

        if (!account.getIsActive()) {
            throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        }

        return buildSession(user, account);
    }

    private AuthResponse buildSession(User user, Account account) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(account.getEmail())
                .code(account.getCode())
                .refreshTokenExpirationMs(jwtUtil.getRefreshExpirationMs())
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
