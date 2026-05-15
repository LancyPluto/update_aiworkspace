package com.aiminilab.aitoolmarket.commerce.controller;

import com.aiminilab.aitoolmarket.commerce.dto.UpsertNodeRequest;
import com.aiminilab.aitoolmarket.commerce.dto.UpsertPlanRequest;
import com.aiminilab.aitoolmarket.commerce.dto.UpsertPoolRequest;
import com.aiminilab.aitoolmarket.commerce.service.CommercePlatformService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/commerce")
public class AdminCommerceController {

    private final CommercePlatformService service;

    public AdminCommerceController(CommercePlatformService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public ApiResponse<List<Map<String, Object>>> plans() {
        return ApiResponse.success(service.adminListPlans());
    }

    @PostMapping("/plans")
    public ApiResponse<Map<String, Object>> upsertPlan(@RequestBody UpsertPlanRequest request) {
        return ApiResponse.success(service.adminUpsertPlan(request));
    }

    @GetMapping("/pools")
    public ApiResponse<List<Map<String, Object>>> pools() {
        return ApiResponse.success(service.adminListPools());
    }

    @PostMapping("/pools")
    public ApiResponse<Map<String, Object>> upsertPool(@RequestBody UpsertPoolRequest request) {
        return ApiResponse.success(service.adminUpsertPool(request));
    }

    @GetMapping("/nodes")
    public ApiResponse<List<Map<String, Object>>> nodes(@RequestParam(required = false) Long poolId) {
        return ApiResponse.success(service.adminListNodes(poolId));
    }

    @PostMapping("/nodes")
    public ApiResponse<Map<String, Object>> upsertNode(@RequestBody UpsertNodeRequest request) {
        return ApiResponse.success(service.adminUpsertNode(request));
    }

    @PostMapping("/nodes/{nodeId}/status")
    public ApiResponse<Map<String, Object>> updateNodeStatus(@PathVariable Long nodeId,
                                                             @RequestParam String status) {
        return ApiResponse.success(service.adminUpdateNodeStatus(nodeId, status));
    }

    @PostMapping("/nodes/{nodeId}/health-check")
    public ApiResponse<Map<String, Object>> healthCheckNode(@PathVariable Long nodeId) {
        return ApiResponse.success(service.adminHealthCheckNode(nodeId));
    }

    @PostMapping("/nodes/health-check")
    public ApiResponse<Map<String, Object>> healthCheckNodes(@RequestParam(required = false) Long poolId) {
        return ApiResponse.success(service.adminHealthCheckNodes(poolId));
    }

    @GetMapping("/capacity-stats")
    public ApiResponse<Map<String, Object>> capacityStats() {
        return ApiResponse.success(service.adminCapacityStats());
    }

    @GetMapping("/orders")
    public ApiResponse<List<Map<String, Object>>> orders() {
        return ApiResponse.success(service.adminListOrders());
    }

    @GetMapping("/model-call-logs")
    public ApiResponse<List<Map<String, Object>>> modelCallLogs() {
        return ApiResponse.success(service.adminListModelCallLogs());
    }

    @GetMapping("/model-feedback")
    public ApiResponse<List<Map<String, Object>>> modelFeedback() {
        return ApiResponse.success(service.adminListModelFeedback());
    }
}
