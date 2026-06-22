package com.aiminilab.aitoolmarket.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.CopyObjectRequest;
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
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssetStorageService {

    private static final Logger log = LoggerFactory.getLogger(AssetStorageService.class);
    private static final String GENERATED_PREFIX = "/generated/";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp");
    private static final Pattern CF_IMAGE_TRANSFORM = Pattern.compile("^(https?://[^/]+)/cdn-cgi/image/[^/]+(/.*)");
    private static final Pattern OSS_PROCESS_SUFFIX = Pattern.compile("^(.*?)\\?x-oss-process=.*$");

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
                || storage.getOssPrivateBucket().isBlank()
                || storage.getOssPublicBucket().isBlank()
                || storage.getOssAccessKeyId().isBlank()
                || storage.getOssAccessKeySecret().isBlank()) {
            throw new IllegalStateException(
                    "ASSET_STORAGE_PROVIDER=oss requires OSS_ENDPOINT, OSS_PUBLIC_BUCKET, OSS_PRIVATE_BUCKET, OSS_ACCESS_KEY_ID, OSS_ACCESS_KEY_SECRET"
            );
        }
        ossClient = new OSSClientBuilder().build(
                normalizeOssEndpoint(storage.getOssEndpoint()),
                storage.getOssAccessKeyId(),
                storage.getOssAccessKeySecret()
        );
        log.info("Asset storage: OSS publicBucket={}, privateBucket={}, prefix={}",
                storage.getOssPublicBucket(), storage.getOssPrivateBucket(), storage.getOssKeyPrefix());
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

    public String getPrivateBaseUrl() {
        return appProperties.getAssetStorage().getPrivateBaseUrl().replaceAll("/+$", "");
    }

    public StoredAsset storeMultipart(String relativeKey, MultipartFile file) {
        return storeMultipartPrivate(relativeKey, file);
    }

    public StoredAsset storeMultipartPublic(String relativeKey, MultipartFile file) {
        return storeMultipartForVisibility(relativeKey, file, AssetVisibility.PUBLIC);
    }

    public StoredAsset storeMultipartPrivate(String relativeKey, MultipartFile file) {
        return storeMultipartForVisibility(relativeKey, file, AssetVisibility.PRIVATE);
    }

    private StoredAsset storeMultipartForVisibility(String relativeKey, MultipartFile file, AssetVisibility visibility) {
        try {
            return storeBytesForVisibility(
                    relativeKey,
                    file.getBytes(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    visibility
            );
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "file save failed");
        }
    }

    public StoredAsset storeBytes(String relativeKey, byte[] data, String contentType) {
        return storeBytesPrivate(relativeKey, data, contentType);
    }

    public StoredAsset storeBytesPublic(String relativeKey, byte[] data, String contentType) {
        return storeBytesForVisibility(relativeKey, data, contentType, AssetVisibility.PUBLIC);
    }

    public StoredAsset storeBytesPrivate(String relativeKey, byte[] data, String contentType) {
        return storeBytesForVisibility(relativeKey, data, contentType, AssetVisibility.PRIVATE);
    }

    private StoredAsset storeBytesForVisibility(String relativeKey, byte[] data, String contentType, AssetVisibility visibility) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        String hashedKey = contentHashKey(normalizedKey, data);
        if (isOssMode()) {
            return storeToOss(hashedKey, data, contentType, visibility);
        }
        return storeToLocal(hashedKey, data);
    }

    public StoredAsset storeStream(String relativeKey, InputStream stream, long size, String contentType) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        try {
            byte[] data = stream.readAllBytes();
            String hashedKey = contentHashKey(normalizedKey, data);
            if (isOssMode()) {
                return storeToOss(hashedKey, data, contentType, AssetVisibility.PRIVATE);
            }
            Path target = localAbsolutePath(hashedKey);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return new StoredAsset(hashedKey, publicUrlForKey(hashedKey), target.toString());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "file save failed");
        }
    }

    public String publicUrlForKey(String relativeKey) {
        return urlForKey(relativeKey, AssetVisibility.PRIVATE);
    }

    public String publicUrlForKey(String relativeKey, AssetVisibility visibility) {
        return urlForKey(relativeKey, visibility);
    }

    public Optional<String> maybeMoveUrl(String url, boolean publish) {
        if (url == null || url.isBlank() || parseManagedAssetUrl(url) == null) {
            return Optional.empty();
        }
        return Optional.of(publish ? moveUrlToPublic(url) : moveUrlToPrivate(url));
    }

    public String moveUrlToPublic(String url) {
        return moveUrl(url, AssetVisibility.PUBLIC);
    }

    public String moveUrlToPrivate(String url) {
        return moveUrl(url, AssetVisibility.PRIVATE);
    }

    public String resolveExistingPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String normalized = url.trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            if (isOssMode()) {
                if (normalized.contains("/cdn/")) {
                    AssetReference ref = parseManagedAssetUrl(normalized);
                    if (ref != null) {
                        return urlForKey(ref.relativeKey(), AssetVisibility.PUBLIC);
                    }
                }
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
            return new StoredAsset(relativeKey, urlForKey(relativeKey, AssetVisibility.PRIVATE), target.toString());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "file save failed");
        }
    }

    private StoredAsset storeToOss(String relativeKey, byte[] data, String contentType, AssetVisibility visibility) {
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        String objectKey = storage.getOssKeyPrefix() + relativeKey;
        String bucket = bucketFor(visibility, storage);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        try {
            ossClient.putObject(bucket, objectKey, new ByteArrayInputStream(data), metadata);
        } catch (RuntimeException exception) {
            log.warn("OSS upload failed: bucket={}, key={}", bucket, objectKey, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "cloud file save failed");
        }
        return new StoredAsset(relativeKey, urlForKey(relativeKey, visibility), "oss://" + bucket + "/" + objectKey);
    }

    private String moveUrl(String url, AssetVisibility targetVisibility) {
        if (url == null || url.isBlank() || !isOssMode()) {
            return url;
        }
        AssetReference source = parseManagedAssetUrl(url);
        if (source == null) {
            return url;
        }
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        String targetBucket = bucketFor(targetVisibility, storage);
        String targetKey = storage.getOssKeyPrefix() + source.relativeKey();
        if (source.bucket().equals(targetBucket) && source.objectKey().equals(targetKey)) {
            if (ossClient.doesObjectExist(targetBucket, targetKey)) {
                return urlForKey(source.relativeKey(), targetVisibility);
            }
            for (String fallback : knownBuckets(storage)) {
                if (fallback.equals(targetBucket)) continue;
                if (ossClient.doesObjectExist(fallback, targetKey)) {
                    ossClient.copyObject(new CopyObjectRequest(fallback, targetKey, targetBucket, targetKey));
                    ossClient.deleteObject(fallback, targetKey);
                    log.info("OSS asset recovered: oss://{}/{} -> oss://{}/{}", fallback, targetKey, targetBucket, targetKey);
                    return urlForKey(source.relativeKey(), targetVisibility);
                }
            }
            log.warn("OSS asset missing from all buckets: key={}", targetKey);
            return urlForKey(source.relativeKey(), targetVisibility);
        }
        try {
            ossClient.copyObject(new CopyObjectRequest(source.bucket(), source.objectKey(), targetBucket, targetKey));
            ossClient.deleteObject(source.bucket(), source.objectKey());
        } catch (RuntimeException exception) {
            log.warn("OSS asset move failed: source=oss://{}/{} target=oss://{}/{}",
                    source.bucket(), source.objectKey(), targetBucket, targetKey, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "cloud file move failed");
        }
        return urlForKey(source.relativeKey(), targetVisibility);
    }

    private String urlForKey(String relativeKey, AssetVisibility visibility) {
        String normalizedKey = normalizeRelativeKey(relativeKey);
        String base = visibility == AssetVisibility.PUBLIC ? getPublicBaseUrl() : getPrivateBaseUrl();
        if (base != null && !base.isBlank()) {
            String rawUrl = base.replaceAll("/+$", "") + "/" + normalizedKey;
            if (visibility == AssetVisibility.PUBLIC && isImageKey(normalizedKey)) {
                return applyImageTransform(rawUrl);
            }
            return rawUrl;
        }
        return GENERATED_PREFIX + normalizedKey;
    }

    private static boolean isImageKey(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) return false;
        return IMAGE_EXTENSIONS.contains(key.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String applyImageTransform(String url) {
        String options = appProperties.getAssetStorage().getImageTransformOptions();
        if (options.isBlank()) return url;
        return url + "?x-oss-process=" + options;
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

    private AssetReference parseManagedAssetUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String normalized = stripImageTransformPrefix(stripQueryAndFragment(url.trim()));
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        for (ConfiguredBase base : configuredBases(storage)) {
            String prefix = base.baseUrl().replaceAll("/+$", "") + "/";
            if (normalized.startsWith(prefix)) {
                String relative = normalizeRelativeKey(normalized.substring(prefix.length()));
                return new AssetReference(base.bucket(), storage.getOssKeyPrefix() + relative, relative);
            }
        }
        // Handle relative proxy paths (e.g. /api/v1/assets/private/images/51/image-1.png)
        String privateBase = storage.getPrivateBaseUrl();
        if (privateBase != null && !privateBase.isBlank() && !privateBase.startsWith("http")) {
            String prefix = privateBase.replaceAll("/+$", "") + "/";
            if (normalized.startsWith(prefix) && !storage.getOssPrivateBucket().isBlank()) {
                String relative = normalizeRelativeKey(normalized.substring(prefix.length()));
                return new AssetReference(storage.getOssPrivateBucket(), storage.getOssKeyPrefix() + relative, relative);
            }
        }
        // Handle legacy /generated/ paths. Some historical public assets were also
        // stored under /generated/, so route them back to the correct bucket by key.
        if (normalized.startsWith(GENERATED_PREFIX) && !storage.getOssPrivateBucket().isBlank()) {
            String relative = normalizeRelativeKey(normalized.substring(GENERATED_PREFIX.length()));
            String bucket = legacyGeneratedBucket(storage, relative);
            return new AssetReference(bucket, storage.getOssKeyPrefix() + relative, relative);
        }
        int cdnPrefixIdx = normalized.indexOf("/cdn/");
        if (cdnPrefixIdx >= 0 && !storage.getOssPublicBucket().isBlank()) {
            String relative = normalizeRelativeKey(normalized.substring(cdnPrefixIdx + "/cdn/".length()));
            return new AssetReference(storage.getOssPublicBucket(), storage.getOssKeyPrefix() + relative, relative);
        }
        String bucketFromHost = bucketFromOssHost(normalized);
        if (bucketFromHost == null || !knownBuckets(storage).contains(bucketFromHost)) {
            return null;
        }
        int pathStart = normalized.indexOf('/', normalized.indexOf("://") + 3);
        if (pathStart <= 0 || pathStart + 1 >= normalized.length()) {
            return null;
        }
        String objectKey = normalizeRelativeKey(normalized.substring(pathStart + 1));
        String prefix = storage.getOssKeyPrefix();
        String relative = prefix.isBlank() || !objectKey.startsWith(prefix)
                ? objectKey
                : objectKey.substring(prefix.length());
        return new AssetReference(bucketFromHost, objectKey, normalizeRelativeKey(relative));
    }

    private static List<ConfiguredBase> configuredBases(AppProperties.AssetStorage storage) {
        List<ConfiguredBase> bases = new ArrayList<>();
        addBase(bases, storage.getPublicBaseUrl(), storage.getOssPublicBucket());
        addBase(bases, storage.getPrivateBaseUrl(), storage.getOssPrivateBucket());
        if (!storage.getOssLegacyBucket().isBlank() && !storage.getOssEndpoint().isBlank()) {
            addBase(bases, "https://" + storage.getOssLegacyBucket() + "." + storage.getOssEndpoint(), storage.getOssLegacyBucket());
        }
        return bases;
    }

    private static void addBase(List<ConfiguredBase> bases, String baseUrl, String bucket) {
        if (baseUrl != null && !baseUrl.isBlank()
                && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                && bucket != null && !bucket.isBlank()) {
            bases.add(new ConfiguredBase(baseUrl, bucket));
        }
    }

    private static Set<String> knownBuckets(AppProperties.AssetStorage storage) {
        LinkedHashSet<String> buckets = new LinkedHashSet<>();
        if (!storage.getOssBucket().isBlank()) buckets.add(storage.getOssBucket());
        if (!storage.getOssPublicBucket().isBlank()) buckets.add(storage.getOssPublicBucket());
        if (!storage.getOssPrivateBucket().isBlank()) buckets.add(storage.getOssPrivateBucket());
        if (!storage.getOssLegacyBucket().isBlank()) buckets.add(storage.getOssLegacyBucket());
        return buckets;
    }

    private static String legacyGeneratedBucket(AppProperties.AssetStorage storage, String relativeKey) {
        String normalized = normalizeRelativeKey(relativeKey);
        if (isLegacyPublicKey(normalized) && !storage.getOssPublicBucket().isBlank()) {
            return storage.getOssPublicBucket();
        }
        return storage.getOssPrivateBucket();
    }

    private static boolean isLegacyPublicKey(String relativeKey) {
        return relativeKey.startsWith("tool-covers/")
                || relativeKey.startsWith("avatars/")
                || relativeKey.startsWith("icons/")
                || relativeKey.startsWith("customer-service/");
    }

    private static String bucketFor(AssetVisibility visibility, AppProperties.AssetStorage storage) {
        return visibility == AssetVisibility.PUBLIC ? storage.getOssPublicBucket() : storage.getOssPrivateBucket();
    }

    private static String bucketFromOssHost(String url) {
        String lower = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        int hostStart = lower.indexOf("://") + 3;
        int hostEnd = lower.indexOf('/', hostStart);
        String host = hostEnd > hostStart ? lower.substring(hostStart, hostEnd) : lower.substring(hostStart);
        int marker = host.indexOf(".oss-");
        return marker > 0 ? host.substring(0, marker) : null;
    }

    private static String stripImageTransformPrefix(String url) {
        Matcher cfMatcher = CF_IMAGE_TRANSFORM.matcher(url);
        if (cfMatcher.matches()) return cfMatcher.group(1) + cfMatcher.group(2);
        Matcher ossMatcher = OSS_PROCESS_SUFFIX.matcher(url);
        if (ossMatcher.matches()) return ossMatcher.group(1);
        return url;
    }

    private static String stripQueryAndFragment(String value) {
        int query = value.indexOf('?');
        int hash = value.indexOf('#');
        int end = value.length();
        if (query >= 0) end = Math.min(end, query);
        if (hash >= 0) end = Math.min(end, hash);
        return value.substring(0, end);
    }

    static String contentHashKey(String relativeKey, byte[] data) {
        int lastSlash = relativeKey.lastIndexOf('/');
        String dir = lastSlash >= 0 ? relativeKey.substring(0, lastSlash + 1) : "";
        String ext = "";
        int dot = relativeKey.lastIndexOf('.');
        if (dot > Math.max(lastSlash, 0)) {
            ext = relativeKey.substring(dot);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return dir + HexFormat.of().formatHex(hash).substring(0, 40) + ext;
        } catch (NoSuchAlgorithmException e) {
            return relativeKey;
        }
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

    public String ossDirectUrl(String relativeKey, String bucket) {
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        String normalizedKey = normalizeRelativeKey(relativeKey);
        String objectKey = storage.getOssKeyPrefix() + normalizedKey;
        String endpoint = storage.getOssEndpoint();
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            int schemeEnd = endpoint.indexOf("://") + 3;
            return endpoint.substring(0, schemeEnd) + bucket + "." + endpoint.substring(schemeEnd) + "/" + objectKey;
        }
        return "https://" + bucket + "." + endpoint + "/" + objectKey;
    }

    public String rewriteResultUrl(String url, boolean forAdmin) {
        if (url == null || url.isBlank()) {
            return url;
        }
        AssetReference ref = parseManagedAssetUrl(url);
        if (ref == null) {
            return url;
        }
        if (forAdmin) {
            if (!isOssMode()) {
                return urlForKey(ref.relativeKey(), AssetVisibility.PRIVATE);
            }
            return ossDirectUrl(ref.relativeKey(), ref.bucket());
        }
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        if (ref.bucket().equals(storage.getOssPublicBucket())) {
            return urlForKey(ref.relativeKey(), AssetVisibility.PUBLIC);
        }
        return urlForKey(ref.relativeKey(), AssetVisibility.PRIVATE);
    }

    public String generateSignedUrl(String relativeKey, AssetVisibility visibility, int expirationSeconds) {
        if (!isOssMode() || ossClient == null) {
            return urlForKey(relativeKey, visibility);
        }
        AppProperties.AssetStorage storage = appProperties.getAssetStorage();
        String bucket = bucketFor(visibility, storage);
        String objectKey = storage.getOssKeyPrefix() + normalizeRelativeKey(relativeKey);
        Date expiration = new Date(System.currentTimeMillis() + (long) expirationSeconds * 1000);
        URL url = ossClient.generatePresignedUrl(bucket, objectKey, expiration);
        return url.toString();
    }

    public String generateSignedPrivateUrl(String relativeKey) {
        return generateSignedUrl(relativeKey, AssetVisibility.PRIVATE, 3600);
    }

    public enum AssetVisibility {
        PUBLIC,
        PRIVATE
    }

    private record AssetReference(String bucket, String objectKey, String relativeKey) {
    }

    private record ConfiguredBase(String baseUrl, String bucket) {
    }
}
