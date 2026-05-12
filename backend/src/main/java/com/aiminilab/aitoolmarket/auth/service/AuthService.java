package com.aiminilab.aitoolmarket.auth.service;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;

public interface AuthService {

    AuthenticatedSession register(RegisterRequest request);

    AuthenticatedSession login(LoginRequest request, boolean adminLogin);

    UserProfileResponse currentUser(Long userId);
}
