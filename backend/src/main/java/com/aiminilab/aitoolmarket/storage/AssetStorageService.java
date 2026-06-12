package com.aiminilab.aitoolmarket.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
public class AssetStorageService {

    private static final Logger log = LoggerFactory.getLogger(AssetStorageService.class);
    private static final String GENERATED_PREFIX = "/generated/";

    private final AppProperties appProperties;
    private final Path localRoot;
    private OSS ossClient;

    public AssetStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.localRoot = Path.of(appProperties.getGeneratedMediaDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        if (!storage.isOss()) {
            return;
        }
        if (storage.getOssEndpoint().isBlank()
                || storage.getOssBucket().isBlank()
                || storage.getOssAccessKeyId().isBlank()
                || storage.getOssAccessKeySecret().isBlank()) {
            throw new IllegalStateException(
                    "ASSET_STORAGE_PROVIDER=oss requires OSS_ENDPOINT, OSS_BUCKET, OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET"
            );
        }
        ossClient = new OSSClientBuilder().build(
                normalizeOssEndpoint(storage.getOssEndpoint()),
                storage.getOssAccessKeyId(),
                storage.getOssAccessKeySecret()
        );
        log.info("Asset storage: OSS bucket={}, prefix={}", storage.getOssBucket(), storage.getOssKeyPrefix());
    }

    @PreDestroy
    void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    public boolean isOssMode() {
        return appProperties.getAssetStorage().isOss();
    }

    public Path getLocalRoot() {
        return localRoot;
    }

    public String getPublicBaseUrl() {
        return appProperties.getAssetStorage().getPublicBaseUrl().replaceAll("/+$", "");
    }

    public StoredAsset storeMultipart(String relativeKey, MultipartFile file) {
        try {
            return storeBytes(
                    relativeKey,
                    file.getBytes(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType()
            );
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
        }
    }

    public StoredAsset storeBytes(String relativeKey, byte[] data, String contentType) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        if (isOssMode()) {
            return storeToOss(normalizedKey, data, contentType);
        }
        return storeToLocal(normalizedKey, data);
    }

    public StoredAsset storeStream(String relativeKey, InputStream stream, long size, String contentType) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        if (isOssMode()) {
            try {
                byte[] data = stream.readAllBytes();
                return storeToOss(normalizedKey, data, contentType);
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
            }
        }
        Path target = localAbsolutePath(normalizedKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(stream, target);
            return new StoredAsset(normalizedKey, publicUrlForKey(normalizedKey), target.toString());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
        }
    }

    public String publicUrlForKey(String relativeKey) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        String base = getPublicBaseUrl();
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return base + "/" + normalizedKey;
        }
        return GENERATED_PREFIX + normalizedKey;
    }

    public String resolveExistingPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String normalized = url.trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            if (isOssMode()) {
                return normalized;
            }
            String relative = relativeKeyFromPublicUrl(normalized);
            if (relative == null) {
                return normalized;
            }
            return Files.exists(localAbsolutePath(relative)) ? normalized : null;
        }
        if (!normalized.startsWith(GENERATED_PREFIX)) {
            return normalized;
        }
        String relative = normalized.substring(GENERATED_PREFIX.length());
        if (relative.isBlank() || relative.contains("..")) {
            return null;
        }
        try {
            relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (isOssMode()) {
            return normalized;
        }
        Path file = localAbsolutePath(relative);
        return Files.exists(file) ? normalized : null;
    }

    public Path localAbsolutePath(String relativeKey) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        Path file = localRoot.resolve(normalizedKey.replace('/', java.io.File.separatorChar)).normalize();
        if (!file.startsWith(localRoot)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid asset path");
        }
        return file;
    }

    public String workerAccessibleUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return publicUrl;
        }
        String trimmed = publicUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        String path = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        String base = appProperties.getAgent().getWorkerMediaBaseUrl();
        if (base == null || base.isBlank()) {
            return path;
        }
        return base.replaceAll("/+$", "") + path;
    }

    private StoredAsset storeToLocal(String relativeKey, byte[] data) {
        Path target = localAbsolutePath(relativeKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return new StoredAsset(relativeKey, publicUrlForKey(relativeKey), target.toString());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
        }
    }

    private StoredAsset storeToOss(String relativeKey, byte[] data, String contentType) {
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        String objectKey = storage.getOssKeyPrefix() + relativeKey;
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        try {
            ossClient.putObject(
                    storage.getOssBucket(),
                    objectKey,
                    new ByteArrayInputStream(data),
                    metadata
            );
        } catch (RuntimeException exception) {
            log.warn("OSS upload failed: key={}", objectKey, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "云端文件保存失败");
        }
        String storagePath = "oss://" + storage.getOssBucket() + "/" + objectKey;
        return new StoredAsset(relativeKey, publicUrlForKey(relativeKey), storagePath);
    }

    private String relativeKeyFromPublicUrl(String url) {
        String base = getPublicBaseUrl();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return null;
        }
        String normalizedBase = base.replaceAll("/+$", "");
        if (!url.startsWith(normalizedBase + "/")) {
            return null;
        }
        return normalizeRelativeKey(url.substring(normalizedBase.length() + 1));
    }

    private static String normalizeRelativeKey(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid asset path");
        }
        String normalized = relativeKey.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..") || normalized.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid asset path");
        }
        return normalized;
    }

    private static String normalizeOssEndpoint(String endpoint) {
        String value = endpoint.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return "https://" + value;
    }
}
