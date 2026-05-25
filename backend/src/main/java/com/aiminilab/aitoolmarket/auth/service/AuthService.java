package com.aiminilab.aitoolmarket.auth.service;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsAuthRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;

public interface AuthService {

    AuthenticatedSession register(RegisterRequest request);

    AuthenticatedSession login(LoginRequest request, boolean adminLogin);

    SmsCodeResponse sendSmsCode(String phone, String scene, String captchaVerifyParam);

    AuthenticatedSession registerWithSmsCode(SmsAuthRequest request);

    AuthenticatedSession loginWithSmsCode(SmsAuthRequest request);

    UserProfileResponse currentUser(Long userId);
}
