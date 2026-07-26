package com.unisage.backend.service.user;

import com.unisage.backend.dto.request.CreateUserRequest;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.dto.request.UpdateUserRequest;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.dto.response.UserResponse;
import com.unisage.backend.dto.response.UserDetailResponse;

import java.util.*;

import com.unisage.backend.entity.Account;
import com.unisage.backend.entity.Permission;
import com.unisage.backend.entity.Role;
import com.unisage.backend.entity.User;
import com.unisage.backend.repository.PermissionRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.repository.RoleRepository;
import com.unisage.backend.repository.AccountRepository;
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

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (request.phone() != null && userRepository.findByPhone(request.phone()).isPresent())
            throw new AppException(ErrorCode.PHONE_EXISTED);

        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));

        User user = User.builder()
                .account(account)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .role(role)
                .phone(request.phone())
                .gender(request.gender())
                .personalEmail(request.personalEmail())
                .build();
        userRepository.save(user);

        return toResponse(account, user);
    }

    @Override
    public UserDetailResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return toDetailResponse(user.getAccount(), user);
    }

    @Override
    public PageResponse<List<UserResponse>> getAllUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PageResponse.fromPage(userPage, u -> toResponse(u.getAccount(), u));
    }

    @Override
    @Transactional
    public UserDetailResponse updateUser(UUID id, UpdateUserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Account account = user.getAccount();

        if (request.email() != null && !request.email().equals(account.getEmail())) {
            if (accountRepository.findByEmail(request.email()).isPresent())
                throw new AppException(ErrorCode.EMAIL_EXISTED);
            account.setEmail(request.email());
        }
        if (request.code() != null && !request.code().equals(account.getCode())) {
            if (accountRepository.findByCode(request.code()).isPresent())
                throw new AppException(ErrorCode.USER_CODE_EXISTED);
            account.setCode(request.code());
        }
        if (request.phone() != null && !request.phone().equals(user.getPhone())) {
            if (userRepository.findByPhone(request.phone()).isPresent())
                throw new AppException(ErrorCode.PHONE_EXISTED);
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPhone(request.phone());
        user.setGender(request.gender());
        user.setAddress(request.address());
        user.setDepartment(request.department());

        accountRepository.save(account);

        if (request.roleId() != null && (user.getRole() == null || !user.getRole().getId().equals(request.roleId()))) {
            Role role = roleRepository.findById(request.roleId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
            user.setRole(role);
        }

        if (request.accountId() != null && !request.accountId().equals(account.getId())) {
            Account newAccount = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new AppException(ErrorCode.ACCOUNT_NOT_EXISTED));
            user.setAccount(newAccount);
            account = newAccount;
        }

        userRepository.save(user);
        return toDetailResponse(account, user);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.setStatus(UserStatus.INACTIVE);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void recoverUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteResources(List<UUID> ids) {
        List<User> users = userRepository.findAllById(ids);
        users.forEach(user -> user.setStatus(UserStatus.INACTIVE));
        userRepository.saveAll(users);
    }

    @Override
    @Transactional
    public void recoverResources(List<UUID> ids) {
        List<User> users = userRepository.findAllById(ids);
        users.forEach(user -> user.setStatus(UserStatus.ACTIVE));
        userRepository.saveAll(users);
    }

    private UserResponse toResponse(Account account, User user) {
        return UserResponse.builder()
                .id(user != null ? user.getId() : account.getId())
                .accountId(account.getId())
                .email(account.getEmail())
                .personalEmail(user != null ? user.getPersonalEmail() : null)
                .firstName(user != null ? user.getFirstName() : null)
                .lastName(user != null ? user.getLastName() : null)
                .avtUrl(user != null ? user.getAvatarUrl() : null)
                .code(account.getCode())
                .roleName(user != null && user.getRole() != null ? user.getRole().getName() : null)
                .roleId(user != null && user.getRole() != null ? user.getRole().getId() : null)
                .phone(user != null ? user.getPhone() : null)
                .gender(user != null ? user.getGender() : null)
                .status(user != null ? user.getStatus() : (account.getIsActive() ? UserStatus.ACTIVE : UserStatus.INACTIVE))
                .lastLogin(account.getLastLogin())
                .createdAt(user != null ? user.getCreatedAt() : account.getCreatedAt())
                .build();
    }

    private UserDetailResponse toDetailResponse(Account account, User user) {
        Map<String, Integer> permissionsMap = new HashMap<>();
        if (user != null && user.getRole() != null && user.getRole().getRolePermissions() != null) {
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
                .id(user != null ? user.getId() : account.getId())
                .accountId(account.getId())
                .email(account.getEmail())
                .personalEmail(user != null ? user.getPersonalEmail() : null)
                .firstName(user != null ? user.getFirstName() : null)
                .lastName(user != null ? user.getLastName() : null)
                .avtUrl(user != null ? user.getAvatarUrl() : null)
                .code(account.getCode())
                .roleName(user != null && user.getRole() != null ? user.getRole().getName() : null)
                .roleId(user != null && user.getRole() != null ? user.getRole().getId() : null)
                .phone(user != null ? user.getPhone() : null)
                .gender(user != null ? user.getGender() : null)
                .status(user != null ? user.getStatus() : (account.getIsActive() ? UserStatus.ACTIVE : UserStatus.INACTIVE))
                .lastLogin(account.getLastLogin())
                .createdAt(user != null ? user.getCreatedAt() : account.getCreatedAt())
                .createdBy(user != null ? user.getCreatedBy() : account.getCreatedBy())
                .updatedAt(user != null ? user.getUpdatedAt() : account.getUpdatedAt())
                .updatedBy(user != null ? user.getUpdatedBy() : account.getUpdatedBy())
                .address(user != null ? user.getAddress() : null)
                .department(user != null ? user.getDepartment() : null)
                .totalQueries(0)
                .topTopics(new ArrayList<>())
                .permissions(permissionsMap)
                .build();
    }
}