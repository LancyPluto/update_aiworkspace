package com.aiminilab.aitoolmarket.admin.controller;

import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeActionResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeListResponse;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeSelectionRequest;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeService;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeTestRequest;
import com.aiminilab.aitoolmarket.admin.proxy.MihomoNodeTestResponse;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/proxy-config/nodes")
public class AdminMihomoNodeController {
    private final MihomoNodeService nodeService;

    public AdminMihomoNodeController(MihomoNodeService nodeService) {
        this.nodeService = nodeService;
    }

    @GetMapping
    public ApiResponse<MihomoNodeListResponse> list() {
        return ApiResponse.success(nodeService.list());
    }

    @PostMapping("/refresh")
    public ApiResponse<MihomoNodeActionResponse> refresh() {
        return ApiResponse.success(nodeService.refreshSubscription());
    }

    @PostMapping("/test-all")
    public ApiResponse<MihomoNodeActionResponse> testAll() {
        return ApiResponse.success(nodeService.testAll());
    }

    @PostMapping("/test")
    public ApiResponse<MihomoNodeTestResponse> test(@RequestBody MihomoNodeTestRequest request) {
        return ApiResponse.success(nodeService.testNode(request));
    }

    @PutMapping("/selection")
    public ApiResponse<MihomoNodeActionResponse> select(@RequestBody MihomoNodeSelectionRequest request) {
        return ApiResponse.success(nodeService.select(request));
    }
}
