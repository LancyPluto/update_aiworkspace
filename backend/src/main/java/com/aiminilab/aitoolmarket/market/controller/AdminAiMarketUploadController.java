package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.UploadIconResponse;
import com.aiminilab.aitoolmarket.market.service.AdminAiMarketToolService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminAiMarketUploadController {

    private final AdminAiMarketToolService adminAiMarketToolService;

    public AdminAiMarketUploadController(AdminAiMarketToolService adminAiMarketToolService) {
        this.adminAiMarketToolService = adminAiMarketToolService;
    }

    @PostMapping("/upload-icon")
    public ApiResponse<UploadIconResponse> uploadIcon(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(adminAiMarketToolService.uploadIcon(file));
    }
}
