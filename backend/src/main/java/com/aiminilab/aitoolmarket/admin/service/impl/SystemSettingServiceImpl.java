package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SystemSettingServiceImpl implements SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingServiceImpl.class);
    private static final long MAX_QR_BYTES = 5 * 1024 * 1024;
    private static final Set<String> QR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final DateTimeFormatter QR_FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String CUSTOMER_SERVICE_QR_SETTING_KEY = "customerService.qrCodeUrl";

    private final SystemSettingMapper systemSettingMapper;
    private final AppProperties appProperties;

    public SystemSettingServiceImpl(SystemSettingMapper systemSettingMapper, AppProperties appProperties) {
        this.systemSettingMapper = systemSettingMapper;
        this.appProperties = appProperties;
    }

    @Override
    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        systemSettingMapper.selectList(null).forEach(setting ->
                result.put(setting.getSettingKey(), setting.getSettingValue()));
        return result;
    }

    @Override
    @Transactional
    public Map<String, String> updateSettings(Map<String, String> settings) {
        settings.forEach((key, value) -> systemSettingMapper.upsert(key, value == null ? "" : value));
        return settings();
    }

    @Override
    @Transactional
    public CustomerServiceQrUploadResponse uploadCustomerServiceQr(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的客服二维码图片");
        }
        if (file.getSize() > MAX_QR_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "客服二维码图片不能超过 5MB");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = resolveQrExtension(originalFilename, file.getContentType());
        if (!QR_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 JPG、PNG、WebP、GIF 格式的二维码图片");
        }

        String filename = "customer-service-" + LocalDateTime.now().format(QR_FILENAME_TIME) + "." + extension;
        Path dir = Path.of(appProperties.getGeneratedMediaDir()).resolve("customer-service").normalize().toAbsolutePath();
        Path target = dir.resolve(filename).normalize();
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException ex) {
            log.warn("Failed to store customer service QR upload: filename={}, contentType={}, size={}",
                    originalFilename, file.getContentType(), file.getSize(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "客服二维码保存失败，请查看后端日志");
        }

        String url = "/generated/customer-service/" + filename;
        systemSettingMapper.upsert(CUSTOMER_SERVICE_QR_SETTING_KEY, url);
        log.info("Admin uploaded customer service QR: url={}, originalFilename={}, contentType={}, size={}",
                url, originalFilename, file.getContentType(), file.getSize());
        return new CustomerServiceQrUploadResponse(
                url,
                filename,
                file.getContentType() == null ? "" : file.getContentType(),
                file.getSize()
        );
    }

    private String resolveQrExtension(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (QR_EXTENSIONS.contains(ext)) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        if (contentType != null) {
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                case "image/gif" -> "gif";
                default -> "";
            };
        }
        return "";
    }
}
