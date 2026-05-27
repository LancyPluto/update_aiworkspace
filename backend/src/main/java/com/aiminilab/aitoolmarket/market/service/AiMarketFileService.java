package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.FileUploadResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AiMarketFileService {

    FileUploadResponse upload(Long userId, MultipartFile file, String toolId);

    List<AiMarketFile> requireOwnedFiles(Long userId, List<String> fileIds);
}
