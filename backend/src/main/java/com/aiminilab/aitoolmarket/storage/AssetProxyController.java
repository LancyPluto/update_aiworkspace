package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.storage.AssetStorageService.AssetVisibility;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Proxies private OSS assets via signed URL 302 redirect.
 * Frontend stores URLs like {@code /api/v1/assets/private/images/123/img.png};
 * this controller generates a time-limited signed OSS URL and redirects.
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetProxyController {

    private final AssetStorageService assetStorageService;

    public AssetProxyController(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    @GetMapping("/private/**")
    public ResponseEntity<Void> privateAsset(HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String prefix = "/api/v1/assets/private/";
        int idx = fullPath.indexOf(prefix);
        if (idx < 0 || fullPath.length() <= idx + prefix.length()) {
            return ResponseEntity.notFound().build();
        }
        String relativeKey = fullPath.substring(idx + prefix.length());
        if (relativeKey.isBlank() || relativeKey.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        String signedUrl = assetStorageService.generateSignedUrl(relativeKey, AssetVisibility.PRIVATE, 3600);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(signedUrl)).build();
    }
}
