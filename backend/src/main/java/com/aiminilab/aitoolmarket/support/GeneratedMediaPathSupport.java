package com.aiminilab.aitoolmarket.support;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class GeneratedMediaPathSupport {

    private final Path mediaRoot;

    public GeneratedMediaPathSupport(AppProperties appProperties) {
        this.mediaRoot = Path.of(appProperties.getGeneratedMediaDir()).toAbsolutePath().normalize();
    }

    public String resolveExistingPublicUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String normalized = url.trim();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        if (!normalized.startsWith("/generated/")) {
            return normalized;
        }
        String relative = normalized.substring("/generated/".length());
        if (relative.isBlank() || relative.contains("..")) {
            return null;
        }
        try {
            relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        Path file = mediaRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!file.startsWith(mediaRoot)) {
            return null;
        }
        return Files.exists(file) ? normalized : null;
    }
}
