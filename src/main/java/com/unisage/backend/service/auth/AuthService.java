package com.unisage.backend.service.auth;

import com.unisage.backend.dto.request.LoginRequest;
import com.unisage.backend.dto.request.SelectProfileRequest;
import com.unisage.backend.dto.response.AuthResponse;
import com.unisage.backend.dto.response.SelectProfileResponse;

public interface AuthService {
    AuthResponse login(LoginRequest request);

    SelectProfileResponse selectProfile(SelectProfileRequest request);

    AuthResponse refresh(String refreshToken);
}