package com.aiminilab.aitoolmarket.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        List<VendorAccount> vendorAccounts,
        List<ModelConfig> modelConfigs,
        List<Category> categories,
        List<Tool> tools
) {
    public record VendorAccount(
            String vendorCode,
            String channelCode,
            String channelLabel,
            String channelIconAsset,
            String accountName,
            String accountRef,
            String baseUrl,
            String apiKey,
            String extraAuthJson,
            Boolean secretsRedacted,
            String consoleUrl,
            String balanceUrl,
            String balanceQueryMode,
            BigDecimal balanceAmount,
            String balanceCurrency,
            BigDecimal balanceLowThreshold,
            Boolean enabled
    ) {
    }

    public record ModelConfig(
            String displayName,
            String configCode,
            String vendorAccountRef,
            String channelCode,
            String channelLabel,
            String channelIconAsset,
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
            List<String> capabilities,
            List<PricingRuleBundle> pricingRules
    ) {
    }

    /** Parameter pricing rules scoped to MODEL (scope_ref = model config id on import). */
    public record PricingRuleBundle(
            String paramKey,
            String ruleType,
            String matchOp,
            String matchValue,
            BigDecimal factor,
            Integer extraCredits,
            Integer priority,
            Boolean enabled,
            String remark
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
            String status,
            Integer version,
            JsonNode nodes,
            JsonNode edges,
            JsonNode groups,
            JsonNode modelConfigIds
    ) {
        public String resolvedNodesJson() {
            if (nodesJson != null && !nodesJson.isBlank()) {
                return nodesJson;
            }
            if (nodes != null && !nodes.isNull() && nodes.isArray()) {
                return nodes.toString();
            }
            return null;
        }

        public String resolvedEdgesJson() {
            if (edgesJson != null && !edgesJson.isBlank()) {
                return edgesJson;
            }
            if (edges != null && !edges.isNull() && edges.isArray()) {
                return edges.toString();
            }
            return null;
        }

        public String resolvedGroupsJson() {
            if (groupsJson != null && !groupsJson.isBlank()) {
                return groupsJson;
            }
            if (groups != null && !groups.isNull() && groups.isArray()) {
                return groups.toString();
            }
            return null;
        }

        public String resolvedConfigJson() {
            if (configJson != null && !configJson.isBlank()) {
                return configJson;
            }
            if (modelConfigIds == null || modelConfigIds.isNull() || modelConfigIds.isEmpty()) {
                return null;
            }
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.set("modelConfigIds", modelConfigIds);
            if (version != null) {
                root.put("version", version);
            }
            return root.toString();
        }
    }
}
