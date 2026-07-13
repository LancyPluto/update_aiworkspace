package com.aiminilab.aitoolmarket.admin.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminOperationAuditInterceptor implements HandlerInterceptor {
    private static final String START_NANOS_ATTRIBUTE = AdminOperationAuditInterceptor.class.getName() + ".startNanos";

    private final AdminOperationAuditService auditService;

    public AdminOperationAuditInterceptor(AdminOperationAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (shouldAudit(request)) {
            request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!shouldAudit(request)) {
            return;
        }
        Object started = request.getAttribute(START_NANOS_ATTRIBUTE);
        long duration = started instanceof Long start ? System.nanoTime() - start : 0L;
        auditService.record(request, response.getStatus(), duration, ex);
    }

    private boolean shouldAudit(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path == null || !path.startsWith("/api/admin/v1/")) {
            return false;
        }
        if (path.startsWith("/api/admin/v1/auth/")) {
            return false;
        }
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || ("GET".equalsIgnoreCase(method) && isSensitiveAgentAuditRead(path));
    }

    private boolean isSensitiveAgentAuditRead(String path) {
        return path.matches("/api/admin/v1/agent/runs/\\d+/(audit|model-requests)")
                || path.equals("/api/admin/v1/agent/skills/coverage");
    }
}
