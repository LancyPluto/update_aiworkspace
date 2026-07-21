package com.aiminilab.aitoolmarket.learning.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.PublicLearningCenterResponse;
import com.aiminilab.aitoolmarket.learning.service.LearningCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learning-center")
public class LearningCenterController {
    private final LearningCenterService learningCenterService;

    public LearningCenterController(LearningCenterService learningCenterService) {
        this.learningCenterService = learningCenterService;
    }

    @GetMapping
    public ApiResponse<PublicLearningCenterResponse> learningCenter() {
        return ApiResponse.success(learningCenterService.publicLearningCenter());
    }
}
