package com.aiminilab.aitoolmarket.common.observability.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/v1/observability")
public class AdminMediaDeliveryController {
    private static final String POLICY_VERSION = "2026-07-14-v1";
    private final AppProperties properties;

    public AdminMediaDeliveryController(AppProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/media-delivery")
    public ApiResponse<MediaDeliveryStatus> status() {
        AppProperties.AssetStorage storage = properties.getAssetStorage();
        List<String> issues = new ArrayList<>();
        if (storage.isOss() && host(storage.getPublicBaseUrl()).isBlank()) issues.add("公共 CDN/OSS 地址未配置");
        if (storage.isOss() && storage.getImageTransformOptions().isBlank()) issues.add("OSS 图片处理参数未配置");
        if (storage.isOss() && !storage.isCdnAuthConfigured()) issues.add("私有 CDN 鉴权未配置，将使用 OSS 签名 URL");
        return ApiResponse.success(new MediaDeliveryStatus(
                storage.getProvider(), host(storage.getPublicBaseUrl()), !storage.getImageTransformOptions().isBlank(),
                storage.isCdnAuthConfigured(), storage.getPublicCacheControl(), storage.getPrivateCacheControl(),
                storage.getLegacyCacheControl(), POLICY_VERSION, issues
        ));
    }

    private String host(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")) return value == null ? "" : value;
        try { return URI.create(value).getHost(); } catch (IllegalArgumentException ignored) { return ""; }
    }

    public record MediaDeliveryStatus(String provider, String publicHost, boolean imageTransformEnabled,
                                      boolean privateCdnAuthConfigured, String publicCacheControl,
                                      String privateCacheControl, String legacyCacheControl,
                                      String policyVersion, List<String> issues) {}
}
