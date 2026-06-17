package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpsertModelVendorRequest;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorService;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/model-vendors")
public class AdminModelVendorController {

    private final ModelVendorService modelVendorService;

    public AdminModelVendorController(ModelVendorService modelVendorService) {
        this.modelVendorService = modelVendorService;
    }

    @GetMapping
    public ApiResponse<List<ModelVendorResponse>> list() {
        return ApiResponse.success(modelVendorService.adminListAll());
    }

    @PutMapping
    public ApiResponse<ModelVendorResponse> upsert(@Valid @RequestBody UpsertModelVendorRequest request) {
        return ApiResponse.success(modelVendorService.adminUpsert(request));
    }

    @DeleteMapping("/{vendorCode}")
    public ApiResponse<Void> delete(@PathVariable String vendorCode) {
        modelVendorService.adminDelete(vendorCode, AuthContext.get().userId());
        return ApiResponse.success(null);
    }
}

