package com.unisage.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import java.util.UUID;

@Builder
public record CreateUserRequest(
    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    String email,

    String code,

    @NotBlank(message = "Mật khẩu không được để trống")
    String password,

    @NotBlank(message = "Tên không được để trống")
    String firstName,

    @NotBlank(message = "Họ không được để trống")
    String lastName,

    @NotNull(message = "Vai trò không được để trống")
    UUID roleId,

    String phone,
    String gender
) {}
