package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.tool.dto.FileUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    private final AppProperties appProperties;

    public UserUploadController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostMapping("/tool-upload")
    public ApiResponse<FileUploadResponse> upload(@RequestParam("file") MultipartFile file) {
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
        Path dir = Path.of(appProperties.getGeneratedMediaDir()).resolve("uploads").resolve(datePath).normalize().toAbsolutePath();
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名无效");
        }

        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException ex) {
            log.warn("Failed to store user upload: originalName={}, contentType={}, size={}",
                    originalName, file.getContentType(), file.getSize(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败，请查看后端日志");
        }

        String url = "/generated/uploads/" + datePath + "/" + filename;
        log.info("User uploaded file: url={}, originalName={}, contentType={}, size={}",
                url, originalName, file.getContentType(), file.getSize());
        return ApiResponse.success(new FileUploadResponse(
                fileId,
                url,
                originalName,
                file.getContentType() == null ? "" : file.getContentType(),
                file.getSize()
        ));
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
}
