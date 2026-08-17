package com.unisage.backend.service.user;
import com.unisage.backend.dto.request.CreateUserRequest;
import com.unisage.backend.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import com.unisage.backend.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.UpdateUserRequest;
import com.unisage.backend.dto.response.UserDetailResponse;

public interface UserService {
 UserResponse createUser(CreateUserRequest request);
 UserDetailResponse getUserById(UUID id);
 PageResponse<List<UserResponse>> getAllUsers(Pageable pageable);
 UserDetailResponse updateUser(UUID id, UpdateUserRequest request);
 void deleteUser(UUID id);
 void recoverUser(UUID id);
 void deleteResources(List<UUID> ids);
 void recoverResources(List<UUID> ids);
 void changePassword(UUID id, String newPassword);
}
