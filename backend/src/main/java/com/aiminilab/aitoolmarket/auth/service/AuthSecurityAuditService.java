package com.aiminilab.aitoolmarket.auth.service;

import jakarta.servlet.http.HttpServletRequest;

public interface AuthSecurityAuditService {

    void record(HttpServletRequest request,
                String eventType,
                String result,
                String method,
                String userType,
                Long userId,
                String account,
                String failureReason);
}
