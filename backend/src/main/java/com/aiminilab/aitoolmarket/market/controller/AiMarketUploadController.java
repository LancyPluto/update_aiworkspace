package com.aiminilab.aitoolmarket.market.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.market.dto.FileUploadResponse;
import com.aiminilab.aitoolmarket.market.service.AiMarketFileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/upload")
public class AiMarketUploadController {

    private final AiMarketFileService aiMarketFileService;

    public AiMarketUploadController(AiMarketFileService aiMarketFileService) {
        this.aiMarketFileService = aiMarketFileService;
    }

    @PostMapping
    public ApiResponse<FileUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "toolId", required = false) String toolId
    ) {
        return ApiResponse.success(aiMarketFileService.upload(AuthContext.get().userId(), file, toolId));
    }
}
