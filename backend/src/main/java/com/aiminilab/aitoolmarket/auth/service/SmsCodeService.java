package com.aiminilab.aitoolmarket.auth.service;

import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;

public interface SmsCodeService {

    SmsCodeResponse sendCode(String phone, String scene);

    void verifyCode(String phone, String scene, String code);
}
