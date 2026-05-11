package com.aiminilab.aitoolmarket.tool.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.tool.dto.FieldSchemaAdminResponse;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/field-schemas")
public class AdminFieldSchemaController {

    private final ToolService toolService;

    public AdminFieldSchemaController(ToolService toolService) {
        this.toolService = toolService;
    }

    @PostMapping("/{schemaId}/publish")
    public ApiResponse<FieldSchemaAdminResponse> publish(@PathVariable Long schemaId) {
        return ApiResponse.success(toolService.publishFieldSchema(schemaId, AuthContext.get().userId()));
    }
}
