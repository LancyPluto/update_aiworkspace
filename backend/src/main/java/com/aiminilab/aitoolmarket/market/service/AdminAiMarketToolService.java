package com.aiminilab.aitoolmarket.market.service;

import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.dto.UpsertAiToolRequest;
import com.aiminilab.aitoolmarket.market.dto.UploadIconResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AdminAiMarketToolService {

    List<AiToolResponse> listAll();

    AiToolResponse create(UpsertAiToolRequest request);

    AiToolResponse update(String toolId, UpsertAiToolRequest request);

    void delete(String toolId);

    UploadIconResponse uploadIcon(MultipartFile file);
}
