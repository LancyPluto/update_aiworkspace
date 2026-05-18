package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/model-providers")
public class AdminModelProviderController {

    private final ModelProviderService modelProviderService;

    public AdminModelProviderController(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    @GetMapping
    public ApiResponse<List<ModelProviderResponse>> list(@RequestParam(required = false) String capability) {
        return ApiResponse.success(modelProviderService.list(capability));
    }

    @GetMapping("/{code}")
    public ApiResponse<ModelProviderResponse> get(@PathVariable String code) {
        return ApiResponse.success(modelProviderService.get(code));
    }
}
