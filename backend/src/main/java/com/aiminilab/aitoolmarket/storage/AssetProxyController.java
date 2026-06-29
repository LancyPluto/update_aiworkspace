package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
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
    private final PrivateAssetAccessService privateAssetAccessService;

    public AssetProxyController(AssetStorageService assetStorageService,
                                PrivateAssetAccessService privateAssetAccessService) {
        this.assetStorageService = assetStorageService;
        this.privateAssetAccessService = privateAssetAccessService;
    }

    @GetMapping("/download/**")
    public ResponseEntity<Void> downloadAsset(HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String prefix = "/api/v1/assets/download/";
        int idx = fullPath.indexOf(prefix);
        if (idx < 0 || fullPath.length() <= idx + prefix.length()) {
            return ResponseEntity.notFound().build();
        }
        String relativeKey = fullPath.substring(idx + prefix.length());
        if (relativeKey.isBlank() || relativeKey.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        if (AuthContext.get() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long userId = AuthContext.get().userId();
        AssetVisibility visibility = assetStorageService.resolveVisibility(relativeKey);
        if (visibility == AssetVisibility.PRIVATE && !privateAssetAccessService.canAccess(userId, relativeKey)) {
            return ResponseEntity.notFound().build();
        }
        String filename = relativeKey.contains("/") ? relativeKey.substring(relativeKey.lastIndexOf('/') + 1) : relativeKey;
        String signedUrl = assetStorageService.generateDownloadSignedUrl(relativeKey, visibility, 3600, filename);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(signedUrl)).build();
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
        if (AuthContext.get() == null
                || !privateAssetAccessService.canAccess(AuthContext.get().userId(), relativeKey)) {
            return ResponseEntity.notFound().build();
        }
        String process = request.getParameter("x-oss-process");
        String signedUrl = assetStorageService.generateSignedUrl(relativeKey, AssetVisibility.PRIVATE, 3600, process);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(signedUrl)).build();
    }
}
