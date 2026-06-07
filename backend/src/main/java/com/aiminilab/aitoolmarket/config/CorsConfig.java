package com.aiminilab.aitoolmarket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AppProperties appProperties;

    public CorsConfig(AuthInterceptor authInterceptor, AppProperties appProperties) {
        this.authInterceptor = authInterceptor;
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        String[] origins = appProperties.getCors().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toArray(String[]::new);
        if (containsWildcard(origins)) {
            registration.allowedOriginPatterns(origins);
        } else {
            registration.allowedOrigins(origins);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String mediaLocation = Path.of(appProperties.getGeneratedMediaDir()).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/generated/**")
                .addResourceLocations(mediaLocation.endsWith("/") ? mediaLocation : mediaLocation + "/");
        // Backward-compatible alias for historical records that still store tool covers as `tool-covers/...`.
        String toolCoverLocation = Path.of(appProperties.getGeneratedMediaDir(), "tool-covers")
                .toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/tool-covers/**")
                .addResourceLocations(toolCoverLocation.endsWith("/") ? toolCoverLocation : toolCoverLocation + "/");
    }

    private boolean containsWildcard(String[] origins) {
        for (String origin : origins) {
            if ("*".equals(origin)) {
                return true;
            }
        }
        return false;
    }
}
