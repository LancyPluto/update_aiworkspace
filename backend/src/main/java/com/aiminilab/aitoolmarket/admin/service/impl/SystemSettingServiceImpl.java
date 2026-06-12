package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceQrUploadResponse;
import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceSettingsResponse;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.cache.CacheNamespaces;
import com.aiminilab.aitoolmarket.admin.dto.SystemSettingVersionResponse;
import com.aiminilab.aitoolmarket.admin.entity.SystemSettingVersion;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingMapper;
import com.aiminilab.aitoolmarket.admin.mapper.SystemSettingVersionMapper;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.AgentMemorySettings;
import com.aiminilab.aitoolmarket.agent.config.AgentPromptSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRouterSettings;
import com.aiminilab.aitoolmarket.agent.config.AgentRuntimeSettings;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
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
import java.util.List;
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

    private final AssetStorageService assetStorageService;
    private final SystemSettingMapper systemSettingMapper;
    private final SystemSettingVersionMapper versionMapper;
    private final BypassCacheService bypassCacheService;

    public SystemSettingServiceImpl(AssetStorageService assetStorageService,
                                    SystemSettingMapper systemSettingMapper,
                                    SystemSettingVersionMapper versionMapper,
                                    BypassCacheService bypassCacheService) {
        this.assetStorageService = assetStorageService;
        this.systemSettingMapper = systemSettingMapper;
        this.versionMapper = versionMapper;
        this.bypassCacheService = bypassCacheService;
    }

    @Override
    public Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<>();
        systemSettingMapper.selectList(null).forEach(setting ->
                result.put(setting.getSettingKey(), setting.getSettingValue()));
        return result;
    }

    @Override
    public CustomerServiceSettingsResponse publicCustomerServiceSettings() {
        return bypassCacheService.getOrLoad(
                CacheNamespaces.PUBLIC_CUSTOMER_SERVICE,
                bypassCacheService.settingsTtl(),
                CustomerServiceSettingsResponse.class,
                () -> CustomerServiceSettingsResponse.from(settings())
        );
    }

    @Override
    @Transactional
    public Map<String, String> updateSettings(Map<String, String> settings) {
        return updateSettings(settings, null);
    }

    @Override
    @Transactional
    public Map<String, String> updateSettings(Map<String, String> settings, Long operatorId) {
        settings.forEach((key, value) -> {
            String normalizedValue = value == null ? "" : value;
            systemSettingMapper.upsert(key, normalizedValue);
            recordVersion(key, normalizedValue, operatorId);
        });
        if (settings.keySet().stream().anyMatch(key -> key != null && key.startsWith("customerService."))) {
            bypassCacheService.invalidatePublicCustomerService();
        }
        return settings();
    }

    @Override
    public List<SystemSettingVersionResponse> settingVersions(String key) {
        return versionMapper.findByKey(key, 10).stream()
                .map(SystemSettingVersionResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public Map<String, String> restoreDefault(String key, Long operatorId) {
        if ("agent".equals(key) || "agent.*".equals(key)) {
            Map<String, String> defaults = agentDefaults();
            defaults.forEach((settingKey, value) -> {
                systemSettingMapper.upsert(settingKey, value);
                recordVersion(settingKey, value, operatorId);
            });
            return settings();
        }
        String value = defaultValueFor(key);
        systemSettingMapper.upsert(key, value);
        recordVersion(key, value, operatorId);
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
        StoredAsset stored;
        try {
            stored = assetStorageService.storeMultipart("customer-service/" + filename, file);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Failed to store customer service QR upload: filename={}, contentType={}, size={}",
                    originalFilename, file.getContentType(), file.getSize(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "客服二维码保存失败，请查看后端日志");
        }
        String url = stored.publicUrl();
        systemSettingMapper.upsert(CUSTOMER_SERVICE_QR_SETTING_KEY, url);
        bypassCacheService.invalidatePublicCustomerService();
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

    private void recordVersion(String key, String value, Long operatorId) {
        if (!isVersionedSetting(key)) {
            return;
        }
        SystemSettingVersion version = new SystemSettingVersion();
        version.setSettingKey(key);
        version.setSettingValue(value);
        version.setOperatorId(operatorId);
        version.setCreatedAt(LocalDateTime.now());
        versionMapper.insertVersion(version);
    }

    private boolean isVersionedSetting(String key) {
        return AgentPromptSettings.SYSTEM_PROMPT_KEY.equals(key)
                || AgentPromptSettings.DEEP_AGENTS_SYSTEM_PROMPT_KEY.equals(key)
                || AgentRouterSettings.PROMPT_KEY.equals(key)
                || AgentMemorySettings.WRITE_PROMPT_KEY.equals(key)
                || AgentMemorySettings.RETRIEVAL_PROMPT_KEY.equals(key)
                || AgentMemorySettings.CONSOLIDATION_PROMPT_KEY.equals(key);
    }

    private String defaultValueFor(String key) {
        String value = agentDefaults().get(key);
        if (value != null) {
            return value;
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "setting key has no default value");
    }

    private Map<String, String> agentDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.putAll(AgentPromptSettings.defaults());
        defaults.putAll(AgentRouterSettings.defaults());
        defaults.putAll(AgentMemorySettings.defaults());
        defaults.putAll(AgentRuntimeSettings.defaults());
        return defaults;
    }
}
