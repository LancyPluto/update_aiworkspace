package com.aiminilab.aitoolmarket.agent.controller;

import com.aiminilab.aitoolmarket.agent.dto.ModelOptionGroupResponse;
import com.aiminilab.aitoolmarket.agent.service.PublicModelOptionService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/model-options")
public class ModelOptionController {

    private final PublicModelOptionService publicModelOptionService;

    public ModelOptionController(PublicModelOptionService publicModelOptionService) {
        this.publicModelOptionService = publicModelOptionService;
    }

    @GetMapping
    public ApiResponse<List<ModelOptionGroupResponse>> list(@RequestParam(required = false) String mode) {
        return ApiResponse.success(publicModelOptionService.list(mode));
    }
}
