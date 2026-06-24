package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import com.aiminilab.aitoolmarket.tool.dto.FileUploadResponse;
import com.aiminilab.aitoolmarket.tool.dto.UserUploadAssetResponse;
import com.aiminilab.aitoolmarket.tool.entity.UserUploadAsset;
import com.aiminilab.aitoolmarket.tool.mapper.UserUploadAssetMapper;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class UserUploadController {

    private static final Logger log = LoggerFactory.getLogger(UserUploadController.class);
    private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "com", "scr", "ps1", "sh", "jar", "war", "dll", "msi"
    );

    private final AssetStorageService assetStorageService;
    private final UserUploadAssetMapper userUploadAssetMapper;

    public UserUploadController(AssetStorageService assetStorageService,
                                UserUploadAssetMapper userUploadAssetMapper) {
        this.assetStorageService = assetStorageService;
        this.userUploadAssetMapper = userUploadAssetMapper;
    }

    @PostMapping("/tool-upload")
    public ApiResponse<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        Long userId = AuthContext.get().userId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传文件不能超过 100MB");
        }

        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (extension.isBlank()) {
            extension = extensionFromContentType(file.getContentType());
        }
        if (extension.isBlank() || BLOCKED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的文件类型");
        }

        String datePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String filename = fileId + "." + extension;
        String relativeKey = "uploads/" + datePath + "/" + filename;
        StoredAsset stored;
        try {
            stored = assetStorageService.storeMultipart(relativeKey, file);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Failed to store user upload: originalName={}, contentType={}, size={}",
                    originalName, file.getContentType(), file.getSize(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败，请查看后端日志");
        }
        String url = stored.publicUrl();
        UserUploadAsset asset = new UserUploadAsset();
        asset.setUserId(userId);
        asset.setFileId(fileId);
        asset.setAssetKind(assetKind(file.getContentType(), extension));
        asset.setOriginalFilename(originalName);
        asset.setContentType(file.getContentType() == null ? "" : file.getContentType());
        asset.setFileSize(file.getSize());
        asset.setUrl(url);
        asset.setStoragePath(stored.storagePath());
        asset.setStatus("ACTIVE");
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(asset.getCreatedAt());
        userUploadAssetMapper.insertAsset(asset);
        log.info("User uploaded file: url={}, originalName={}, contentType={}, size={}",
                url, originalName, file.getContentType(), file.getSize());
        String displayUrl = assetStorageService.rewriteResultUrl(url, false);
        return ApiResponse.success(new FileUploadResponse(
                asset.getId(),
                fileId,
                displayUrl,
                originalName,
                file.getContentType() == null ? "" : file.getContentType(),
                file.getSize()
        ));
    }

    @GetMapping("/upload-assets")
    public ApiResponse<PageResponse<UserUploadAssetResponse>> recent(@RequestParam(value = "kind", required = false) String kind,
                                                                     @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                                     @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        String normalizedKind = normalizeKind(kind);
        Long userId = AuthContext.get().userId();
        long total = userUploadAssetMapper.countActiveByUser(userId, normalizedKind);
        List<UserUploadAssetResponse> items = userUploadAssetMapper
                .findRecentByUser(userId, normalizedKind, normalizedPageSize, offset)
                .stream()
                .map(UserUploadAssetResponse::from)
                .map(item -> item.withRewrittenUrl(assetStorageService.rewriteResultUrl(item.url(), false)))
                .toList();
        return ApiResponse.success(PageResponse.of(items, total, pageNo, pageSize));
    }

    @DeleteMapping("/upload-assets/{assetId}")
    public ApiResponse<Void> deleteAsset(@PathVariable Long assetId) {
        userUploadAssetMapper.softDelete(AuthContext.get().userId(), assetId, LocalDateTime.now());
        return ApiResponse.success(null);
    }

    private String safeOriginalName(String value) {
        String normalized = Normalizer.normalize(value == null ? "upload" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .trim();
        return normalized.isBlank() ? "upload" : Path.of(normalized).getFileName().toString();
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot >= filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).trim().toLowerCase();
    }

    private String extensionFromContentType(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/flac", "audio/x-flac" -> "flac";
            case "audio/ogg" -> "ogg";
            case "audio/aac", "audio/x-aac" -> "aac";
            case "application/pdf" -> "pdf";
            default -> "";
        };
    }

    private String assetKind(String contentType, String extension) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.startsWith("image/")) return "image";
        if (type.startsWith("video/")) return "video";
        if (type.startsWith("audio/")) return "audio";
        if (Set.of("jpg", "jpeg", "png", "webp", "gif").contains(extension)) return "image";
        if (Set.of("mp4", "webm", "mov").contains(extension)) return "video";
        if (Set.of("mp3", "wav", "m4a", "flac", "ogg", "aac").contains(extension)) return "audio";
        return "file";
    }

    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String normalized = kind.trim().toLowerCase();
        return Set.of("image", "video", "audio", "file").contains(normalized) ? normalized : null;
    }
}
