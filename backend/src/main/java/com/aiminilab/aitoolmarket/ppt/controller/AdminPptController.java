package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.ppt.service.PptEngineClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/ppt")
public class AdminPptController {

    private final PptEngineClient pptEngineClient;

    public AdminPptController(PptEngineClient pptEngineClient) {
        this.pptEngineClient = pptEngineClient;
    }

    @GetMapping("/engine-health")
    public ApiResponse<Map<String, Object>> engineHealth() {
        boolean healthy = pptEngineClient.healthCheck();
        return ApiResponse.success(Map.of(
                "healthy", healthy,
                "status", healthy ? "UP" : "DOWN"
        ));
    }
}
