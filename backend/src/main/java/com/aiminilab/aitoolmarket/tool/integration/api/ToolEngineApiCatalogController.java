package com.aiminilab.aitoolmarket.tool.integration.api;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/integration-plugins")
public class ToolEngineApiCatalogController {

    @GetMapping("/{pluginId}/api-catalog")
    public ApiResponse<ToolIntegrationApiPluginDefinition> getCatalog(@PathVariable String pluginId) {
        return ApiResponse.success(ToolIntegrationApiCatalog.require(pluginId));
    }
}
