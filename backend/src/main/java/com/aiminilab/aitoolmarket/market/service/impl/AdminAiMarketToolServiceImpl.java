package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import com.aiminilab.aitoolmarket.market.dto.AiToolResponse;
import com.aiminilab.aitoolmarket.market.dto.UpsertAiToolRequest;
import com.aiminilab.aitoolmarket.market.dto.UploadIconResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketToolMapper;
import com.aiminilab.aitoolmarket.market.service.AdminAiMarketToolService;
import com.aiminilab.aitoolmarket.market.support.AiMarketToolValidator;
import com.aiminilab.aitoolmarket.market.support.CapabilitiesCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminAiMarketToolServiceImpl implements AdminAiMarketToolService {

    private static final Set<String> ICON_EXTENSIONS = Set.of("jpg", "jpeg", "png", "svg", "webp");

    private final AiMarketToolMapper aiMarketToolMapper;
    private final CapabilitiesCodec capabilitiesCodec;
    private final AiMarketToolValidator validator;
    private final AssetStorageService assetStorageService;

    public AdminAiMarketToolServiceImpl(
            AiMarketToolMapper aiMarketToolMapper,
            CapabilitiesCodec capabilitiesCodec,
            AiMarketToolValidator validator,
            AssetStorageService assetStorageService
    ) {
        this.aiMarketToolMapper = aiMarketToolMapper;
        this.capabilitiesCodec = capabilitiesCodec;
        this.validator = validator;
        this.assetStorageService = assetStorageService;
    }

    @Override
    public List<AiToolResponse> listAll() {
        return aiMarketToolMapper.findAllActive().stream()
                .map(tool -> AiToolResponse.from(tool, capabilitiesCodec))
                .toList();
    }

    @Override
    @Transactional
    public AiToolResponse create(UpsertAiToolRequest request) {
        validator.validateUpsert(request, true);
        if (aiMarketToolMapper.countByToolId(request.id()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工具 id 已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        AiMarketTool tool = toEntity(request, request.id(), now);
        aiMarketToolMapper.insertTool(tool);
        return AiToolResponse.from(aiMarketToolMapper.findByToolId(tool.getToolId()), capabilitiesCodec);
    }

    @Override
    @Transactional
    public AiToolResponse update(String toolId, UpsertAiToolRequest request) {
        validator.validateUpsert(request, false);
        AiMarketTool existing = aiMarketToolMapper.findOptionalByToolId(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        LocalDateTime now = LocalDateTime.now();
        AiMarketTool tool = toEntity(request, existing.getToolId(), now);
        if (aiMarketToolMapper.updateTool(tool) == 0) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在");
        }
        return AiToolResponse.from(aiMarketToolMapper.findByToolId(toolId), capabilitiesCodec);
    }

    @Override
    @Transactional
    public void delete(String toolId) {
        if (aiMarketToolMapper.softDelete(toolId) == 0) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在");
        }
    }

    @Override
    public UploadIconResponse uploadIcon(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "file 不能为空");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ICON_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 jpg/png/svg/webp 图标");
        }
        String filename = UUID.randomUUID() + "." + extension;
        StoredAsset stored = assetStorageService.storeMultipart("icons/" + filename, file);
        return new UploadIconResponse(stored.publicUrl());
    }

    private AiMarketTool toEntity(UpsertAiToolRequest request, String toolId, LocalDateTime now) {
        AiMarketTool tool = new AiMarketTool();
        tool.setToolId(toolId);
        tool.setName(request.name().trim());
        tool.setIconUrl(request.iconUrl().trim());
        tool.setDescription(request.description());
        tool.setEnabled(request.enabled());
        tool.setSortOrder(request.order());
        tool.setPrimaryColor(request.primaryColor());
        tool.setWelcomeMessage(request.welcomeMessage());
        tool.setCapabilitiesJson(capabilitiesCodec.serialize(request.capabilities()));
        tool.setCreatedAt(now);
        tool.setUpdatedAt(now);
        tool.setDeleted(false);
        return tool;
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
