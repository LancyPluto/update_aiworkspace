package com.aiminilab.aitoolmarket.admin.audit;

import jakarta.servlet.http.HttpServletRequest;

public interface AdminOperationAuditService {
    void record(HttpServletRequest request, int status, long durationNanos, Throwable error);
}
