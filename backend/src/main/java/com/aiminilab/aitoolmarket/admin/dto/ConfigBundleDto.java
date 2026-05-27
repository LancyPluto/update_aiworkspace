package com.aiminilab.aitoolmarket.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ConfigBundleDto(
        String format,
        Integer version,
        String exportedAt,
        String exportedBy,
        Boolean secretsRedacted,
        Map<String, String> settings,
        List<ModelConfig> modelConfigs,
        List<Category> categories,
        List<Tool> tools
) {
    public record ModelConfig(
            String displayName,
            String configCode,
            String provider,
            String modelName,
            String baseUrl,
            String apiKey,
            String extraAuthJson,
            Boolean secretsRedacted,
            String minimaxGroupId,
            String consoleUrl,
            String balanceUrl,
            String docsUrl,
            Integer timeoutSeconds,
            Integer connectTimeoutSeconds,
            Integer readTimeoutSeconds,
            BigDecimal inputTokenPricePer1k,
            BigDecimal outputTokenPricePer1k,
            BigDecimal inputTokenPricePer1m,
            BigDecimal outputTokenPricePer1m,
            String billingUnit,
            BigDecimal unitPrice,
            Boolean enabled,
            Boolean agentEnabled,
            Boolean isDefault,
            List<String> capabilities
    ) {
    }

    public record Category(
            String categoryCode,
            String categoryName,
            Integer sortOrder,
            String status
    ) {
    }

    public record Tool(
            String toolCode,
            String toolName,
            String categoryCode,
            String description,
            String coverUrl,
            String toolType,
            String inputModality,
            String outputModality,
            String configNote,
            String status,
            Integer estimatedCreditCost,
            String modelConfigCode,
            String executionHandler,
            Boolean agentEnabled,
            List<Field> fields,
            List<Prompt> prompts,
            Workflow workflow
    ) {
    }

    public record Field(
            String fieldKey,
            String fieldName,
            String fieldType,
            String placeholder,
            JsonNode options,
            String optionsJson,
            Boolean required,
            Boolean executionRequired,
            Boolean userRequired,
            String defaultValue,
            String agentFillStrategy,
            String riskLevel,
            Integer sortOrder
    ) {
    }

    public record Prompt(
            String promptCode,
            String promptName,
            String status,
            String activeVersionNo,
            List<PromptVersion> versions
    ) {
    }

    public record PromptVersion(
            String versionNo,
            String systemPrompt,
            String userPromptTemplate,
            String outputFormat,
            String status
    ) {
    }

    public record Workflow(
            String workflowName,
            String nodesJson,
            String edgesJson,
            String groupsJson,
            String configJson,
            String status
    ) {
    }
}
