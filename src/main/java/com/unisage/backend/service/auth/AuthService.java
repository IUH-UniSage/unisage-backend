package com.unisage.backend.service.auth;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse login(LoginRequest request);

    AuthResponse refresh(String refreshToken);
}
