package com.aiminilab.aitoolmarket.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void openApiDocumentsCurrentImplementedApiSurface() throws Exception {
        String openApi = Files.readString(openApiPath(), StandardCharsets.UTF_8);

        assertThat(openApi).contains("openapi: 3.0.3");
        assertThat(openApi).doesNotContain("�", "宸", "绠", "鐢", "浠", "閫", "鍙", "鏌", "璐");

        List<String> requiredPaths = List.of(
                "/api/health:",
                "/api/v1/auth/register:",
                "/api/v1/auth/login:",
                "/api/v1/users/me:",
                "/api/v1/tool-categories:",
                "/api/v1/tools:",
                "/api/v1/tools/search:",
                "/api/v1/tools/{toolCode}:",
                "/api/v1/tasks:",
                "/api/v1/tasks/{taskId}:",
                "/api/v1/tasks/{taskId}/status:",
                "/api/v1/tasks/{taskId}/cancel:",
                "/api/v1/tasks/{taskId}/regenerate:",
                "/api/v1/credits/account:",
                "/api/v1/credits/logs:",
                "/api/v1/credits/statement-logs:",
                "/api/v1/credits/usage-logs:",
                "/api/admin/v1/auth/login:",
                "/api/admin/v1/tools:",
                "/api/admin/v1/tools/{toolId}:",
                "/api/admin/v1/tools/{toolId}/fields:",
                "/api/admin/v1/tools/{toolId}/field-schemas:",
                "/api/admin/v1/tools/{toolId}/apply-template:",
                "/api/admin/v1/tool-templates:",
                "/api/admin/v1/tool-templates/{templateCode}:",
                "/api/admin/v1/field-schemas/{schemaId}/publish:",
                "/api/admin/v1/tasks:",
                "/api/admin/v1/tasks/{taskId}:",
                "/api/admin/v1/tasks/{taskId}/retry:",
                "/api/admin/v1/tasks/{taskId}/cancel:",
                "/api/admin/v1/users:",
                "/api/admin/v1/users/{userId}:",
                "/api/admin/v1/users/{userId}/status:",
                "/api/admin/v1/users/{userId}/credits/account:",
                "/api/admin/v1/users/{userId}/credits/logs:",
                "/api/admin/v1/users/{userId}/credits/manual-add:",
                "/api/admin/v1/users/{userId}/credits/manual-deduct:",
                "/api/admin/v1/model-providers:",
                "/api/admin/v1/model-providers/{code}:",
                "/api/internal/v1/tasks/{taskId}/execution-context:",
                "/api/internal/v1/tasks/{taskId}/processing:",
                "/api/internal/v1/tasks/{taskId}/success:",
                "/api/internal/v1/tasks/{taskId}/failed:"
        );

        assertThat(openApi).contains(requiredPaths.toArray(String[]::new));
        assertThat(openApi).contains(
                "available:",
                "PageBase:",
                "ManualCreditRequest:",
                "AdminUser:",
                "RegenerateTaskRequest:",
                "internalApiToken:",
                "ModelProviderDescriptor:"
        );
    }

    private static Path openApiPath() {
        List<Path> candidates = List.of(
                Path.of("..", "docs", "api", "openapi.yml"),
                Path.of("docs", "api", "openapi.yml"),
                Path.of("/docs", "api", "openapi.yml")
        );
        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("docs/api/openapi.yml not found"));
    }
}
