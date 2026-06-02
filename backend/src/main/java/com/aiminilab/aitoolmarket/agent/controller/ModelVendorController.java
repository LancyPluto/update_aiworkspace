package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/model-vendors")
public class ModelVendorController {

    private final ModelVendorService modelVendorService;

    public ModelVendorController(ModelVendorService modelVendorService) {
        this.modelVendorService = modelVendorService;
    }

    @GetMapping
    public ApiResponse<List<ModelVendorResponse>> listEnabled() {
        return ApiResponse.success(modelVendorService.listEnabled());
    }
}

