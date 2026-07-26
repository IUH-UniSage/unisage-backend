package com.unisage.backend.dto.request;

import jakarta.validation.constraints.Email;
import lombok.Builder;
import java.util.UUID;

@Builder
public record UpdateUserRequest(
    String firstName,
    String lastName,
    @Email
    String email,
    UUID roleId,
    UUID accountId,
    String code,
    String phone,
    String gender,
    String department,
    String address
) {}
