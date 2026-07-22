package com.aiminilab.aitoolmarket.learning.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CategoryRequest;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CategoryResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CoverUploadResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.TutorialRequest;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.TutorialResponse;
import com.aiminilab.aitoolmarket.learning.service.LearningCenterService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/learning-center")
public class AdminLearningCenterController {
    private final LearningCenterService learningCenterService;

    public AdminLearningCenterController(LearningCenterService learningCenterService) {
        this.learningCenterService = learningCenterService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success(learningCenterService.adminCategories());
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ApiResponse.success(learningCenterService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<CategoryResponse> updateCategory(@PathVariable Long id,
                                                        @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.success(learningCenterService.updateCategory(id, request));
    }

    @DeleteMapping("/categories/{id}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        learningCenterService.deleteCategory(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/tutorials")
    public ApiResponse<List<TutorialResponse>> tutorials(@RequestParam(required = false) Long categoryId) {
        return ApiResponse.success(learningCenterService.adminTutorials(categoryId));
    }

    @PostMapping("/tutorials")
    public ApiResponse<TutorialResponse> createTutorial(@Valid @RequestBody TutorialRequest request) {
        return ApiResponse.success(learningCenterService.createTutorial(request));
    }

    @PutMapping("/tutorials/{id}")
    public ApiResponse<TutorialResponse> updateTutorial(@PathVariable Long id,
                                                        @Valid @RequestBody TutorialRequest request) {
        return ApiResponse.success(learningCenterService.updateTutorial(id, request));
    }

    @DeleteMapping("/tutorials/{id}")
    public ApiResponse<Void> deleteTutorial(@PathVariable Long id) {
        learningCenterService.deleteTutorial(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/covers/upload")
    public ApiResponse<CoverUploadResponse> uploadCover(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(learningCenterService.uploadCover(file));
    }
}
