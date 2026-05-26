package com.aiminilab.aitoolmarket.market.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
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
    private final AppProperties appProperties;

    public AiMarketFileServiceImpl(
            AiMarketFileMapper aiMarketFileMapper,
            AiMarketToolService aiMarketToolService,
            CapabilitiesCodec capabilitiesCodec,
            AppProperties appProperties
    ) {
        this.aiMarketFileMapper = aiMarketFileMapper;
        this.aiMarketToolService = aiMarketToolService;
        this.capabilitiesCodec = capabilitiesCodec;
        this.appProperties = appProperties;
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
        String storedPath = storeFile(userId, file);
        LocalDateTime now = LocalDateTime.now();
        AiMarketFile record = new AiMarketFile();
        record.setFileId(fileId);
        record.setUserId(userId);
        record.setToolId(tool == null ? null : tool.getToolId());
        record.setOriginalName(safeFilename(file.getOriginalFilename()));
        record.setStoragePath(storedPath);
        record.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        record.setFileSize(file.getSize());
        record.setCreatedAt(now);
        aiMarketFileMapper.insertFile(record);
        return new FileUploadResponse(fileId, toPublicUrl(storedPath));
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

    private String storeFile(Long userId, MultipartFile file) {
        try {
            Path root = Path.of(appProperties.getGeneratedMediaDir()).resolve("market-files").resolve(String.valueOf(userId))
                    .toAbsolutePath().normalize();
            Files.createDirectories(root);
            String filename = UUID.randomUUID() + "-" + safeFilename(file.getOriginalFilename());
            Path stored = root.resolve(filename).normalize();
            if (!stored.startsWith(root)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid filename");
            }
            Files.write(stored, file.getBytes());
            return stored.toString();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件存储失败");
        }
    }

    private String toPublicUrl(String storagePath) {
        Path root = Path.of(appProperties.getGeneratedMediaDir()).toAbsolutePath().normalize();
        Path stored = Path.of(storagePath).toAbsolutePath().normalize();
        if (!stored.startsWith(root)) {
            return storagePath;
        }
        Path relative = root.relativize(stored);
        return "/generated/" + relative.toString().replace('\\', '/');
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
