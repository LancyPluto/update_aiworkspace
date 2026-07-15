package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Service
public class MihomoRuntimeService {
    private final SystemSettingService systemSettingService;
    private final MihomoConfigRenderer renderer;
    private final MihomoRuntimeProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private volatile Instant lastAppliedAt;
    private volatile String lastMessage = "";

    @Autowired
    public MihomoRuntimeService(
            SystemSettingService systemSettingService,
            MihomoConfigRenderer renderer,
            MihomoRuntimeProperties properties,
            ObjectMapper objectMapper
    ) {
        this(systemSettingService, renderer, properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), objectMapper);
    }

    MihomoRuntimeService(
            SystemSettingService systemSettingService,
            MihomoConfigRenderer renderer,
            MihomoRuntimeProperties properties,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this.systemSettingService = systemSettingService;
        this.renderer = renderer;
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public MihomoRuntimeResponse status() {
        if (!properties.isEnabled()) {
            return unmanaged();
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request("/version").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return managedUnavailable("Mihomo controller returned HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String version = body.path("version").asText("");
            return new MihomoRuntimeResponse(true, true, version, lastAppliedAt,
                    lastMessage.isBlank() ? "Mihomo controller is available" : lastMessage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return managedUnavailable("Mihomo controller request was interrupted");
        } catch (Exception exception) {
            return managedUnavailable("Mihomo controller is unavailable: " + conciseMessage(exception));
        }
    }

    public MihomoRuntimeResponse apply() {
        if (!properties.isEnabled()) {
            return unmanaged();
        }
        try {
            Map<String, String> settings = systemSettingService.settings();
            String yaml = renderer.render(settings, properties.getControllerSecret());
            writeAtomically(properties.getConfigPath(), yaml);

            String body = objectMapper.writeValueAsString(Map.of("path", properties.getReloadPath()));
            HttpResponse<String> response = httpClient.send(
                    request("/configs?force=true")
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastMessage = "Configuration written, but Mihomo reload returned HTTP " + response.statusCode();
                return managedUnavailable(lastMessage);
            }
            lastAppliedAt = Instant.now();
            lastMessage = "Configuration applied to Mihomo";
            return new MihomoRuntimeResponse(true, true, "", lastAppliedAt, lastMessage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            lastMessage = "Mihomo reload was interrupted";
            return managedUnavailable(lastMessage);
        } catch (Exception exception) {
            lastMessage = "Failed to apply Mihomo configuration: " + conciseMessage(exception);
            return managedUnavailable(lastMessage);
        }
    }

    private HttpRequest.Builder request(String path) {
        String baseUrl = properties.getControllerUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5));
        String secret = properties.getControllerSecret();
        if (secret != null && !secret.isBlank()) {
            builder.header("Authorization", "Bearer " + secret);
        }
        return builder;
    }

    private void writeAtomically(Path destination, String content) throws IOException {
        Path absoluteDestination = destination.toAbsolutePath();
        Path parent = absoluteDestination.getParent();
        if (parent == null) {
            throw new IOException("Mihomo config path has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".mihomo-config-", ".yaml.tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absoluteDestination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteDestination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private MihomoRuntimeResponse unmanaged() {
        return new MihomoRuntimeResponse(false, false, "", null,
                "Mihomo is not managed by this deployment");
    }

    private MihomoRuntimeResponse managedUnavailable(String message) {
        return new MihomoRuntimeResponse(true, false, "", lastAppliedAt, message);
    }

    private String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
