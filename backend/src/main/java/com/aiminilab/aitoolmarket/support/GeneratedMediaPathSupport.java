package com.aiminilab.aitoolmarket.support;

import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import org.springframework.stereotype.Component;

@Component
public class GeneratedMediaPathSupport {

    private final AssetStorageService assetStorageService;

    public GeneratedMediaPathSupport(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    public String resolveExistingPublicUrl(String url) {
        return assetStorageService.resolveExistingPublicUrl(url);
    }
}
