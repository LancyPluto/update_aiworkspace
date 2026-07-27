package com.aiminilab.aitoolmarket.ppt.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptExecutionTokenRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptExecutionTokenResponse;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelInvocationRequest;
import com.aiminilab.aitoolmarket.ppt.dto.PptModelInvocationView;
import com.aiminilab.aitoolmarket.ppt.service.PptModelInvocationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/ppt")
public class InternalPptModelController {
    private final PptModelInvocationService invocationService;

    public InternalPptModelController(PptModelInvocationService invocationService) {
        this.invocationService = invocationService;
    }

    @PostMapping("/execution-tokens")
    public ApiResponse<PptExecutionTokenResponse> issue(
            @Valid @RequestBody PptExecutionTokenRequest request) {
        var token = invocationService.issue(
                request.projectId(), request.jobId(), request.capabilities());
        return ApiResponse.success(new PptExecutionTokenResponse(
                token.token(), token.expiresAtEpochSeconds()));
    }

    @PostMapping("/model-invocations")
    public ApiResponse<PptModelInvocationView> create(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody PptModelInvocationRequest request) {
        return ApiResponse.success(invocationService.create(authorization, request));
    }

    @GetMapping("/model-invocations/{invocationId}")
    public ApiResponse<PptModelInvocationView> get(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable Long invocationId) {
        return ApiResponse.success(invocationService.get(authorization, invocationId));
    }
}
