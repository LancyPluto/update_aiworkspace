package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import com.aiminilab.aitoolmarket.market.dto.FileUploadResponse;
import com.aiminilab.aitoolmarket.market.entity.AiMarketFile;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketFileMapper;
import com.aiminilab.aitoolmarket.market.service.AiMarketFileService;
import com.aiminilab.aitoolmarket.market.service.AiMarketToolService;
import com.aiminilab.aitoolmarket.market.support.CapabilitiesCodec;
import com.aiminilab.aitoolmarket.market.support.FileReadingPolicy;
import com.aiminilab.aitoolmarket.market.support.MarketIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AiMarketFileServiceImpl implements AiMarketFileService {

    private final AiMarketFileMapper aiMarketFileMapper;
    private final AiMarketToolService aiMarketToolService;
    private final CapabilitiesCodec capabilitiesCodec;
    private final AssetStorageService assetStorageService;

    public AiMarketFileServiceImpl(
            AiMarketFileMapper aiMarketFileMapper,
            AiMarketToolService aiMarketToolService,
            CapabilitiesCodec capabilitiesCodec,
            AssetStorageService assetStorageService
    ) {
        this.aiMarketFileMapper = aiMarketFileMapper;
        this.aiMarketToolService = aiMarketToolService;
        this.capabilitiesCodec = capabilitiesCodec;
        this.assetStorageService = assetStorageService;
    }

    @Override
    @Transactional
    public FileUploadResponse upload(Long userId, MultipartFile file, String toolId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "file 不能为空");
        }
        FileReadingPolicy.Policy policy;
        AiMarketTool tool = null;
        if (toolId != null && !toolId.isBlank()) {
            tool = aiMarketToolService.requireTool(toolId);
            policy = FileReadingPolicy.resolve(tool, capabilitiesCodec);
        } else {
            policy = new FileReadingPolicy.Policy(
                    FileReadingPolicy.defaultExtensions(),
                    FileReadingPolicy.defaultMaxMb()
            );
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!policy.supportedExtensions().contains(extension)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "不支持的文件类型");
        }
        if (file.getSize() > policy.maxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED, "文件大小超过限制");
        }

        String fileId = MarketIdGenerator.fileId();
        String filename = UUID.randomUUID() + "-" + safeFilename(file.getOriginalFilename());
        StoredAsset stored = assetStorageService.storeMultipart("market-files/" + userId + "/" + filename, file);
        LocalDateTime now = LocalDateTime.now();
        AiMarketFile record = new AiMarketFile();
        record.setFileId(fileId);
        record.setUserId(userId);
        record.setToolId(tool == null ? null : tool.getToolId());
        record.setOriginalName(safeFilename(file.getOriginalFilename()));
        record.setStoragePath(stored.storagePath());
        record.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        record.setFileSize(file.getSize());
        record.setCreatedAt(now);
        aiMarketFileMapper.insertFile(record);
        return new FileUploadResponse(fileId, stored.publicUrl());
    }

    @Override
    public List<AiMarketFile> requireOwnedFiles(Long userId, List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<AiMarketFile> files = aiMarketFileMapper.findByFileIdsAndUser(userId, fileIds);
        if (files.size() != fileIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "存在无效或未授权的附件");
        }
        return files;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "upload.bin" : Path.of(filename).getFileName().toString();
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
