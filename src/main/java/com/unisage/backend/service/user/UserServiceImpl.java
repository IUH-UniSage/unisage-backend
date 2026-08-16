package com.unisage.backend.service.user;

import com.unisage.backend.dto.request.CreateUserRequest;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.dto.request.UpdateUserRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UserResponse;
import com.unisage.backend.dto.response.UserDetailResponse;

import java.time.LocalDateTime;
import java.util.*;

import com.unisage.backend.entity.Permission;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.service.user.UserService;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent())
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        if (request.code() != null && userRepository.findByCode(request.code()).isPresent())
            throw new AppException(ErrorCode.USER_CODE_EXISTED);
        if (request.phone() != null && userRepository.findByPhone(request.phone()).isPresent())
            throw new AppException(ErrorCode.PHONE_EXISTED);

        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        User user = User.builder()
                .email(request.email())
                .code(request.code())
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(role)
                .phone(request.phone())
                .gender(request.gender())
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);

        return toResponse(user);
    }

    @Override
    public UserDetailResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return toDetailResponse(user);
    }

    @Override
    public PageResponse<List<UserResponse>> getAllUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PageResponse.fromPage(userPage, this::toResponse);
    }

    @Override
    @Transactional
    public UserDetailResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (request.email() != null && !request.email().equals(user.getEmail())) {
            if (userRepository.findByEmail(request.email()).isPresent())
                throw new AppException(ErrorCode.EMAIL_EXISTED);
            user.setEmail(request.email());
        }
        if (request.code() != null && !request.code().equals(user.getCode())) {
            if (userRepository.findByCode(request.code()).isPresent())
                throw new AppException(ErrorCode.USER_CODE_EXISTED);
            user.setCode(request.code());
        }
        if (request.phone() != null && !request.phone().equals(user.getPhone())) {
            if (userRepository.findByPhone(request.phone()).isPresent())
                throw new AppException(ErrorCode.PHONE_EXISTED);
            user.setPhone(request.phone());
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setGender(request.gender());
        user.setExtraInfo(request.extraInfo());

        if (request.roleId() != null && (user.getRole() == null || !user.getRole().getId().equals(request.roleId()))) {
            Role role = roleRepository.findById(request.roleId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
            user.setRole(role);
        }

        userRepository.save(user);
        return toDetailResponse(user);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.setStatus(UserStatus.INACTIVE);
        user.setDeletedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void recoverUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.setStatus(UserStatus.ACTIVE);
        user.setDeletedAt(null);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteResources(List<UUID> ids) {
        List<User> users = userRepository.findAllById(ids);
        users.forEach(user -> {
            user.setStatus(UserStatus.INACTIVE);
            user.setDeletedAt(LocalDateTime.now());
        });
        userRepository.saveAll(users);
    }

    @Override
    @Transactional
    public void recoverResources(List<UUID> ids) {
        List<User> users = userRepository.findAllById(ids);
        users.forEach(user -> {
            user.setStatus(UserStatus.ACTIVE);
            user.setDeletedAt(null);
        });
        userRepository.saveAll(users);
    }

    @Override
    @Transactional
    public void changePassword(UUID id, String newPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .avatarUrl(user.getAvatarUrl())
                .code(user.getCode())
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .phone(user.getPhone())
                .gender(user.getGender())
                .status(user.getStatus())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private UserDetailResponse toDetailResponse(User user) {
        Map<String, Integer> permissionsMap = new HashMap<>();
        if (user.getRole() != null && user.getRole().getRolePermissions() != null) {
            user.getRole().getRolePermissions().forEach(rp -> {
                Permission p = rp.getPermission();
                Integer level = p.getAccessLevel();
                if (level == null) {
                    permissionsMap.putIfAbsent(p.getName(), null);
                } else {
                    permissionsMap.merge(p.getName(), level, (oldVal, newVal) ->
                            oldVal == null ? newVal : Math.max(oldVal, newVal));
                }
            });
        }

        return UserDetailResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .avatarUrl(user.getAvatarUrl())
                .code(user.getCode())
                .roleName(user.getRole() != null ? user.getRole().getName() : null)
                .roleId(user.getRole() != null ? user.getRole().getId() : null)
                .phone(user.getPhone())
                .gender(user.getGender())
                .status(user.getStatus())
                .extraInfo(user.getExtraInfo())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .createdBy(user.getCreatedBy())
                .updatedAt(user.getUpdatedAt())
                .updatedBy(user.getUpdatedBy())
                .totalQueries(0)
                .topTopics(new ArrayList<>())
                .permissions(permissionsMap)
                .build();
    }
}
