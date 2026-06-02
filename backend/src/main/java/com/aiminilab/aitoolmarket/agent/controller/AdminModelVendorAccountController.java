package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/model-vendor-accounts")
public class AdminModelVendorAccountController {

    private final ModelVendorAccountService modelVendorAccountService;

    public AdminModelVendorAccountController(ModelVendorAccountService modelVendorAccountService) {
        this.modelVendorAccountService = modelVendorAccountService;
    }

    @GetMapping
    public ApiResponse<List<ModelVendorAccountResponse>> list(@RequestParam(required = false) String vendorCode) {
        return ApiResponse.success(modelVendorAccountService.adminList(vendorCode));
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelVendorAccountResponse> get(@PathVariable Long id) {
        return ApiResponse.success(modelVendorAccountService.adminGet(id));
    }

    @PostMapping
    public ApiResponse<ModelVendorAccountResponse> create(@Valid @RequestBody ModelVendorAccountRequest request) {
        return ApiResponse.success(modelVendorAccountService.adminCreate(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelVendorAccountResponse> update(@PathVariable Long id,
                                                          @Valid @RequestBody ModelVendorAccountRequest request) {
        return ApiResponse.success(modelVendorAccountService.adminUpdate(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        modelVendorAccountService.adminDelete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ModelVendorAccountTestResponse> test(@PathVariable Long id) {
        return ApiResponse.success(modelVendorAccountService.adminTest(id));
    }

    @PostMapping("/{id}/refresh-balance")
    public ApiResponse<ModelVendorAccountResponse> refreshBalance(@PathVariable Long id) {
        return ApiResponse.success(modelVendorAccountService.adminRefreshBalance(id));
    }

    @PostMapping("/refresh-balance-all")
    public ApiResponse<Map<String, Integer>> refreshBalanceAll() {
        return ApiResponse.success(Map.of("refreshed", modelVendorAccountService.adminRefreshBalanceAll()));
    }
}
