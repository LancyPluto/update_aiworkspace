package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketFileMapper;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.tool.mapper.UserUploadAssetMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Service
public class PrivateAssetAccessService {

    private static final String PRIVATE_PROXY_PREFIX = "/api/v1/assets/private/";
    private static final String GENERATED_PREFIX = "/generated/";
    private static final Set<String> TASK_ASSET_PREFIXES = Set.of(
            "images", "image", "audio", "video", "videos", "digital-human"
    );

    private final AppProperties appProperties;
    private final UserUploadAssetMapper userUploadAssetMapper;
    private final AiMarketFileMapper aiMarketFileMapper;
    private final TaskMapper taskMapper;
    private final AssetStorageService assetStorageService;
    private final AgentFileMapper agentFileMapper;

    public PrivateAssetAccessService(AppProperties appProperties,
                                     UserUploadAssetMapper userUploadAssetMapper,
                                     AiMarketFileMapper aiMarketFileMapper,
                                     TaskMapper taskMapper,
                                     AssetStorageService assetStorageService,
                                     AgentFileMapper agentFileMapper) {
        this.appProperties = appProperties;
        this.userUploadAssetMapper = userUploadAssetMapper;
        this.aiMarketFileMapper = aiMarketFileMapper;
        this.taskMapper = taskMapper;
        this.assetStorageService = assetStorageService;
        this.agentFileMapper = agentFileMapper;
    }

    public boolean canAccess(Long userId, String relativeKey) {
        if (userId == null) {
            return false;
        }
        String key = normalizeRelativeKey(relativeKey);
        if (key == null) {
            return false;
        }
        String[] segments = key.split("/");
        if (segments.length < 2) {
            return false;
        }
        String prefix = segments[0].toLowerCase(Locale.ROOT);
        if ("uploads".equals(prefix)) {
            return userUploadAssetMapper.countActiveByUserAndRelativeKey(userId, key) > 0;
        }
        if ("market-files".equals(prefix)) {
            return aiMarketFileMapper.countByUserAndRelativeKey(userId, key) > 0;
        }
        if ("agent-attachments".equals(prefix)) {
            if (!userId.equals(parsePositiveLong(segments[1])) || segments.length < 3) {
                return false;
            }
            String fileIdPart = segments[2].split("-", 2)[0];
            Long fileId = parsePositiveLong(fileIdPart);
            return fileId != null && agentFileMapper.countReadyByIdAndUser(fileId, userId) > 0;
        }
        if (TASK_ASSET_PREFIXES.contains(prefix)) {
            Long taskId = parsePositiveLong(segments[1]);
            return taskId != null && taskMapper.countOwnedTask(taskId, userId) > 0;
        }
        return false;
    }

    public String requireOwnedStableUrl(Long userId, String rawUrl) {
        String relativeKey = privateRelativeKey(rawUrl);
        if (relativeKey == null) {
            return rawUrl;
        }
        if (!canAccess(userId, relativeKey)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "private asset owner mismatch");
        }
        return rawUrl;
    }

    public String resolveForWorker(Long userId, String rawUrl) {
        String relativeKey = privateRelativeKey(rawUrl);
        if (relativeKey == null) {
            return rawUrl;
        }
        if (!canAccess(userId, relativeKey)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "private asset owner mismatch");
        }
        if (assetStorageService.isOssMode()) {
            return assetStorageService.generateSignedPrivateUrl(relativeKey);
        }
        return assetStorageService.workerAccessibleUrl("/generated/" + relativeKey);
    }

    public String privateRelativeKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String path = pathOnly(rawUrl.trim());
        String fromProxy = stripPrefix(path, PRIVATE_PROXY_PREFIX);
        if (fromProxy != null) {
            return normalizeRelativeKey(fromProxy);
        }
        String fromGenerated = stripPrefix(path, GENERATED_PREFIX);
        if (fromGenerated != null) {
            return normalizeRelativeKey(fromGenerated);
        }
        String privateBase = appProperties.getAssetStorage().getPrivateBaseUrl();
        if (privateBase != null && !privateBase.isBlank()) {
            String basePath = pathOnly(privateBase.trim()).replaceAll("/+$", "") + "/";
            String fromConfiguredBase = stripPrefix(path, basePath);
            if (fromConfiguredBase != null) {
                return normalizeRelativeKey(fromConfiguredBase);
            }
        }
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        if (!storage.getOssPrivateBucket().isBlank() && !storage.getOssEndpoint().isBlank()) {
            String endpoint = storage.getOssEndpoint()
                    .replaceFirst("^https?://", "")
                    .replaceAll("/+$", "");
            String directPrivateBase = "/" + storage.getOssKeyPrefix();
            try {
                URI uri = URI.create(rawUrl.trim());
                String expectedHost = storage.getOssPrivateBucket() + "." + endpoint;
                if (expectedHost.equalsIgnoreCase(uri.getHost())) {
                    String directPath = uri.getPath() == null ? "" : uri.getPath();
                    String fromDirectBucket = stripPrefix(directPath, directPrivateBase);
                    if (fromDirectBucket != null) {
                        return normalizeRelativeKey(fromDirectBucket);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String pathOnly(String value) {
        String withoutFragment = value.split("[?#]", 2)[0];
        if (!withoutFragment.startsWith("http://") && !withoutFragment.startsWith("https://")) {
            return withoutFragment.startsWith("/") ? withoutFragment : "/" + withoutFragment;
        }
        try {
            String path = URI.create(withoutFragment).getPath();
            return path == null || path.isBlank() ? "/" : path;
        } catch (IllegalArgumentException ignored) {
            return "/";
        }
    }

    private static String stripPrefix(String value, String prefix) {
        int index = value.indexOf(prefix);
        return index < 0 ? null : value.substring(index + prefix.length());
    }

    private static String normalizeRelativeKey(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            return null;
        }
        String normalized = relativeKey.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..") || normalized.contains("//")) {
            return null;
        }
        return normalized;
    }

    private static Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
