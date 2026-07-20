package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.util.Utf8TextRepair;
import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.credit.dto.ModelPricingSnapshot;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidationResult;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowDslService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class WorkflowServiceImpl implements WorkflowService {

    private static final String DSL_VERSION = "1";
    private static final String NODE_REGISTRY_VERSION = "p0";
    private static final String RISK_POLICY_JSON =
            "{\"confirmationPolicy\":\"WORKFLOW_DEFINED\",\"level\":\"MEDIUM\"}";

    private final ToolWorkflowMapper workflowMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowDslService workflowDslService;
    private final ToolMapper toolMapper;
    private final ToolFieldItemMapper toolFieldItemMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final PricingService pricingService;
    private final BypassCacheService bypassCacheService;

    public WorkflowServiceImpl(ToolWorkflowMapper workflowMapper,
                               ToolWorkflowVersionMapper versionMapper,
                               ObjectMapper objectMapper,
                               WorkflowDslService workflowDslService,
                               ToolMapper toolMapper,
                               ToolFieldItemMapper toolFieldItemMapper,
                               AgentModelConfigMapper modelConfigMapper,
                               PricingService pricingService,
                               BypassCacheService bypassCacheService) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.workflowDslService = workflowDslService;
        this.toolMapper = toolMapper;
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.pricingService = pricingService;
        this.bypassCacheService = bypassCacheService;
    }

    @Override
    public WorkflowResponse getWorkflow(Long toolId) {
        return workflowMapper.findByToolId(toolId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Override
    public WorkflowResponse getWorkflowById(Long workflowId) {
        return toResponse(workflowMapper.selectById(workflowId));
    }

    @Override
    @Transactional
    public WorkflowResponse saveWorkflow(Long toolId, UpsertWorkflowRequest request, Long operatorId) {
        ToolWorkflow existing = workflowMapper.selectByToolId(toolId);
        if (existing == null) {
            if (request.expectedDraftRevision() != 0L) {
                throw draftConflict(0L);
            }
            ToolWorkflow workflow = new ToolWorkflow();
            workflow.setToolId(toolId);
            workflow.setWorkflowName(request.workflowName());
            workflow.setNodesJson(repairJson(request.nodesJson()));
            workflow.setEdgesJson(repairJson(request.edgesJson()));
            workflow.setGroupsJson(repairJson(request.groupsJson()));
            workflow.setConfigJson(repairJson(request.configJson()));
            workflowMapper.insertWorkflow(workflow, operatorId);
            return toResponse(workflowMapper.selectById(workflow.getId()));
        }

        int changed = workflowMapper.updateDraftIfRevision(
                existing.getId(),
                repairJson(request.nodesJson()),
                repairJson(request.edgesJson()),
                repairJson(request.groupsJson()),
                repairJson(request.configJson()),
                request.expectedDraftRevision(),
                operatorId
        );
        if (changed != 1) {
            ToolWorkflow latest = workflowMapper.selectById(existing.getId());
            throw draftConflict(latest == null ? 0L : revision(latest));
        }
        return toResponse(workflowMapper.selectById(existing.getId()));
    }

    @Override
    public java.util.List<WorkflowVersionItemResponse> listVersions(Long workflowId, int pageNo, int pageSize) {
        int offset = Math.max(0, (pageNo - 1)) * Math.max(1, pageSize);
        return versionMapper.selectByWorkflowId(workflowId, pageSize, offset)
                .stream()
                .map(v -> new WorkflowVersionItemResponse(
                        v.getId(),
                        v.getVersion(),
                        v.getDslHash(),
                        v.getSourceDraftRevision(),
                        v.getSnapshotLabel(),
                        v.getPublishedBy(),
                        v.getPublishedAt()
                ))
                .toList();
    }

    @Override
    @Transactional
    public WorkflowResponse restoreVersion(Long workflowId, int targetVersion, Long operatorId) {
        ToolWorkflow current = requireWorkflow(workflowId);
        ToolWorkflowVersion snapshot = versionMapper.selectByWorkflowIdAndVersion(workflowId, targetVersion);
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流版本不存在: " + targetVersion);
        }
        int changed = workflowMapper.restoreDraftIfRevision(
                workflowId,
                snapshot.getNodesJson(),
                snapshot.getEdgesJson(),
                snapshot.getGroupsJson(),
                snapshot.getConfigJson(),
                revision(current),
                operatorId
        );
        if (changed != 1) {
            throw draftConflict(revision(requireWorkflow(workflowId)));
        }
        return toResponse(requireWorkflow(workflowId));
    }

    @Override
    @Transactional
    public WorkflowResponse publish(Long workflowId, Long operatorId) {
        ToolWorkflow draft = requireWorkflow(workflowId);
        AiTool lockedTool = toolMapper.selectByIdForUpdate(draft.getToolId());
        if (lockedTool == null) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "Tool not found");
        }
        boolean executionEnabled = isWorkflowToolOnline(lockedTool);
        long draftRevision = revision(draft);
        WorkflowDslValidationResult validation = workflowDslService.validate(draft);
        if (!validation.valid()) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "工作流校验未通过: " + String.join("; ", validation.errors())
            );
        }

        PublicationArtifacts artifacts = publicationArtifacts(draft);
        if (toolMapper.updateWorkflowMinimumRequiredCredits(
                draft.getToolId(), artifacts.minimumRequiredCredits(), operatorId) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Workflow minimum credits update failed");
        }
        if (executionEnabled) {
            workflowMapper.disableAllExecutionsPreservingPublication(draft.getToolId(), operatorId);
        }

        ToolWorkflowVersion currentPublished = draft.getPublishedVersionId() == null
                ? null
                : versionMapper.selectById(draft.getPublishedVersionId());
        if (currentPublished != null
                && Objects.equals(currentPublished.getSourceDraftRevision(), draftRevision)
                && Objects.equals(currentPublished.getDslHash(), artifacts.fingerprint())) {
            bindPublishedVersion(draft, currentPublished, executionEnabled, operatorId);
            invalidateToolCatalogAfterCommit(lockedTool.getToolCode());
            return toResponse(requireWorkflow(workflowId));
        }

        ToolWorkflowVersion identical = versionMapper.selectByWorkflowIdAndDslHash(
                workflowId, artifacts.fingerprint());
        if (identical != null
                && Objects.equals(identical.getSourceDraftRevision(), draftRevision)) {
            bindPublishedVersion(draft, identical, executionEnabled, operatorId);
            invalidateToolCatalogAfterCommit(lockedTool.getToolCode());
            return toResponse(requireWorkflow(workflowId));
        }

        ToolWorkflowVersion published = publishedSnapshot(
                draft,
                versionMapper.selectMaxVersion(workflowId) + 1,
                artifacts,
                operatorId
        );
        versionMapper.insert(published);
        bindPublishedVersion(draft, published, executionEnabled, operatorId);
        invalidateToolCatalogAfterCommit(lockedTool.getToolCode());
        return toResponse(requireWorkflow(workflowId));
    }

    @Override
    @Transactional
    public void disableExecutionsForToolPreservingPublication(Long toolId, Long operatorId) {
        workflowMapper.disableAllExecutionsPreservingPublication(toolId, operatorId);
    }

    private void bindPublishedVersion(ToolWorkflow draft,
                                      ToolWorkflowVersion published,
                                      boolean executionEnabled,
                                      Long operatorId) {
        int changed = workflowMapper.bindPublishedVersion(
                draft.getId(),
                published.getId(),
                published.getVersion(),
                executionEnabled,
                revision(draft),
                operatorId
        );
        if (changed != 1) {
            throw draftConflict(revision(requireWorkflow(draft.getId())));
        }
    }

    private ToolWorkflowVersion publishedSnapshot(ToolWorkflow draft,
                                                   int version,
                                                   PublicationArtifacts artifacts,
                                                   Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        ToolWorkflowVersion snapshot = new ToolWorkflowVersion();
        snapshot.setWorkflowId(draft.getId());
        snapshot.setVersion(version);
        snapshot.setNodesJson(repairJson(draft.getNodesJson()));
        snapshot.setEdgesJson(repairJson(draft.getEdgesJson()));
        snapshot.setGroupsJson(repairJson(draft.getGroupsJson()));
        snapshot.setConfigJson(repairJson(draft.getConfigJson()));
        snapshot.setCanonicalDslJson(artifacts.canonicalDslJson());
        snapshot.setDslVersion(DSL_VERSION);
        snapshot.setNodeRegistryVersion(NODE_REGISTRY_VERSION);
        snapshot.setDslHash(artifacts.fingerprint());
        snapshot.setInputSchemaSnapshotJson(artifacts.inputSchemaSnapshotJson());
        snapshot.setDependencyManifestJson(artifacts.dependencyManifestJson());
        snapshot.setBillingPolicyJson(artifacts.billingPolicyJson());
        snapshot.setRiskPolicyJson(artifacts.riskPolicyJson());
        snapshot.setSourceDraftRevision(revision(draft));
        snapshot.setSnapshotLabel("Published v" + version);
        snapshot.setPublishedAt(now);
        snapshot.setPublishedBy(operatorId);
        snapshot.setCreatedAt(now);
        snapshot.setCreatedBy(operatorId);
        return snapshot;
    }

    private String billingPolicyJson(ToolWorkflow draft) {
        AiTool tool = toolMapper.selectById(draft.getToolId());
        int estimated = tool == null || tool.getEstimatedCreditCost() == null
                ? 0
                : Math.max(0, tool.getEstimatedCreditCost());
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("mode", "WORKFLOW_STEP");
        policy.put("fallbackMaxCreditCost", estimated);
        policy.put("fallbackChargeCredits", estimated);
        ObjectNode nodePolicies = objectMapper.createObjectNode();
        WorkflowDsl dsl = workflowDslService.parse(
                draft.getNodesJson(), draft.getEdgesJson(), draft.getConfigJson()
        );
        for (WorkflowNodeDef node : dsl.nodes()) {
            if (!node.type().isWorkerStep()) {
                continue;
            }
            Long modelConfigId = node.parameters() != null && node.parameters().hasNonNull("modelConfigId")
                    ? node.parameters().get("modelConfigId").asLong()
                    : null;
            AgentModelConfig modelConfig = modelConfigId == null
                    ? null
                    : modelConfigMapper.findActiveById(modelConfigId);
            if (modelConfigId != null && modelConfig == null) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "Workflow node references an inactive model configuration: " + node.id()
                );
            }
            int explicitCap = node.parameters() != null && node.parameters().hasNonNull("maxCreditCost")
                    ? Math.max(0, node.parameters().get("maxCreditCost").asInt())
                    : 0;
            int maxCreditCost = explicitCap > 0 ? explicitCap : estimated;
            if (maxCreditCost <= 0) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "Paid workflow worker step has no reservation cap: " + node.id()
                );
            }
            JsonNode providerCapNode = node.parameters() == null
                    ? null
                    : node.parameters().get("maxProviderCostCny");
            if (providerCapNode == null || !providerCapNode.isNumber()) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "Paid workflow worker step has no provider cost cap: " + node.id()
                );
            }
            BigDecimal maxProviderCostCny = providerCapNode.decimalValue();
            if (maxProviderCostCny.signum() <= 0
                    || maxProviderCostCny.stripTrailingZeros().scale() > 6) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "Paid workflow worker step has an invalid provider cost cap: " + node.id()
                );
            }
            maxProviderCostCny = maxProviderCostCny.setScale(6);
            if (maxProviderCostCny.precision() - maxProviderCostCny.scale() > 12) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "Paid workflow worker step provider cost cap is too large: " + node.id()
                );
            }
            ObjectNode nodePolicy = objectMapper.createObjectNode();
            nodePolicy.put("maxCreditCost", maxCreditCost);
            nodePolicy.put("maxProviderCostCny", maxProviderCostCny);
            nodePolicy.put("fallbackChargeCredits", estimated);
            nodePolicy.set("staticParams", node.parameters() == null
                    ? objectMapper.createObjectNode()
                    : node.parameters());
            nodePolicy.set("modelPricingSnapshot", objectMapper.valueToTree(ModelPricingSnapshot.from(modelConfig)));
            nodePolicy.set("pricingPolicy", objectMapper.valueToTree(pricingService.snapshot(tool, modelConfig)));
            nodePolicies.set(node.id(), nodePolicy);
        }
        policy.set("nodePolicies", nodePolicies);
        return writeJson(policy);
    }

    private String inputSchemaSnapshotJson(Long toolId) {
        List<ToolFieldResponse> fields = toolFieldItemMapper.findActiveFields(toolId).stream()
                .map(field -> ToolFieldResponse.from(field, objectMapper))
                .toList();
        ObjectNode schema = objectMapper.createObjectNode();
        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();
        schema.put("type", "object");
        for (ToolFieldResponse field : fields) {
            if (field.fieldKey() == null || field.fieldKey().isBlank()) {
                continue;
            }
            ObjectNode property = objectMapper.createObjectNode();
            applyFieldType(property, field.fieldType());
            property.put("x-field-type", field.fieldType());
            if (field.fieldName() != null && !field.fieldName().isBlank()) {
                property.put("title", field.fieldName());
            }
            if (field.placeholder() != null && !field.placeholder().isBlank()) {
                property.put("description", field.placeholder());
            }
            if (field.sortOrder() != null) {
                property.put("x-sort-order", field.sortOrder());
            }
            JsonNode options = enumOptions(field.options());
            boolean booleanEnum = isBooleanModeField(field) && allBooleanEnumOptions(options);
            if (booleanEnum) {
                property.put("type", "boolean");
            }
            if (supportsEnum(field.fieldType()) && options != null) {
                ArrayNode enumValues = objectMapper.createArrayNode();
                options.forEach(option -> {
                    String value = option.isTextual()
                            ? option.asText()
                            : option.hasNonNull("value") ? option.get("value").asText() : null;
                    if (value != null) {
                        if (booleanEnum) {
                            enumValues.add(booleanValue(value));
                        } else {
                            enumValues.add(value);
                        }
                    }
                });
                if (!enumValues.isEmpty()) {
                    property.set("enum", enumValues);
                }
            }
            applyDefaultValue(property, field.defaultValue());
            boolean userRequired = Boolean.TRUE.equals(
                    field.userRequired() == null ? field.required() : field.userRequired());
            property.put("x-user-required", userRequired);
            property.put("x-agent-fill-strategy",
                    field.agentFillStrategy() == null || field.agentFillStrategy().isBlank()
                            ? (userRequired ? "ask_user" : "default")
                            : field.agentFillStrategy());
            property.put("x-risk-level",
                    field.riskLevel() == null || field.riskLevel().isBlank() ? "LOW" : field.riskLevel());
            properties.set(field.fieldKey(), property);
            if (Boolean.TRUE.equals(
                    field.executionRequired() == null ? field.required() : field.executionRequired())) {
                required.add(field.fieldKey());
            }
        }
        schema.set("properties", properties);
        schema.set("required", required);
        return writeJson(schema);
    }

    private void applyFieldType(ObjectNode property, String fieldType) {
        if ("multi_image".equalsIgnoreCase(fieldType) || "multi_video".equalsIgnoreCase(fieldType)) {
            property.put("type", "array");
            property.putObject("items").put("type", "string");
            return;
        }
        if ("omni_video_list".equalsIgnoreCase(fieldType)) {
            property.put("type", "array");
            ObjectNode item = property.putObject("items");
            item.put("type", "object");
            ObjectNode itemProperties = item.putObject("properties");
            itemProperties.putObject("video_url").put("type", "string");
            itemProperties.putObject("refer_type").put("type", "string");
            itemProperties.putObject("keep_original_sound").put("type", "string");
            return;
        }
        if ("subject_element_list".equalsIgnoreCase(fieldType)) {
            property.put("type", "array");
            property.putObject("items").put("type", "object");
            return;
        }
        property.put("type", jsonType(fieldType));
        if ("textarea".equalsIgnoreCase(fieldType)) {
            property.put("format", "textarea");
        }
    }

    private boolean isWorkflowToolOnline(AiTool tool) {
        return "ONLINE".equalsIgnoreCase(tool.getStatus())
                && "WORKFLOW".equalsIgnoreCase(tool.getExecutionMode())
                && "WORKFLOW_STEP".equalsIgnoreCase(tool.getBillingMode())
                && Boolean.TRUE.equals(tool.getAgentSurfaceEnabled());
    }

    private PublicationArtifacts publicationArtifacts(ToolWorkflow draft) {
        String canonicalDslJson = canonicalDsl(draft);
        String inputSchemaSnapshotJson = inputSchemaSnapshotJson(draft.getToolId());
        String dependencyManifestJson = canonicalJson(draft.getConfigJson(), "{}");
        String billingPolicyJson = billingPolicyJson(draft);
        String riskPolicyJson = canonicalJson(RISK_POLICY_JSON, "{}");
        int minimumRequiredCredits = minimumRequiredCredits(draft, billingPolicyJson);
        ObjectNode publication = objectMapper.createObjectNode();
        publication.set("canonicalDsl", readJson(canonicalDslJson, objectMapper.createObjectNode()));
        publication.put("dslVersion", DSL_VERSION);
        publication.put("nodeRegistryVersion", NODE_REGISTRY_VERSION);
        publication.set("inputSchema", readJson(inputSchemaSnapshotJson, objectMapper.createObjectNode()));
        publication.set("dependencies", readJson(dependencyManifestJson, objectMapper.createObjectNode()));
        publication.set("billingPolicy", readJson(billingPolicyJson, objectMapper.createObjectNode()));
        publication.set("riskPolicy", readJson(riskPolicyJson, objectMapper.createObjectNode()));
        String fingerprint = sha256(writeJson(canonicalNode(publication)));
        return new PublicationArtifacts(
                canonicalDslJson,
                inputSchemaSnapshotJson,
                dependencyManifestJson,
                billingPolicyJson,
                riskPolicyJson,
                minimumRequiredCredits,
                fingerprint
        );
    }

    private int minimumRequiredCredits(ToolWorkflow draft, String billingPolicyJson) {
        WorkflowDsl dsl = workflowDslService.parse(
                draft.getNodesJson(), draft.getEdgesJson(), draft.getConfigJson());
        JsonNode nodePolicies = readJson(
                billingPolicyJson, objectMapper.createObjectNode()).path("nodePolicies");
        for (String nodeId : dsl.executionOrder()) {
            WorkflowNodeDef node = dsl.requireNode(nodeId);
            if (!node.type().isWorkerStep()) {
                continue;
            }
            int maxCreditCost = nodePolicies.path(nodeId).path("maxCreditCost").asInt(0);
            if (maxCreditCost > 0) {
                return maxCreditCost;
            }
        }
        return 0;
    }

    private void applyDefaultValue(ObjectNode property, String defaultValue) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return;
        }
        String type = property.path("type").asText("string");
        try {
            switch (type) {
                case "boolean" -> property.put("default", booleanValue(defaultValue));
                case "integer" -> property.put("default", Long.parseLong(defaultValue.trim()));
                case "number" -> property.put("default", new BigDecimal(defaultValue.trim()));
                case "string" -> property.put("default", defaultValue);
                default -> {
                    // Complex field defaults are intentionally omitted unless represented structurally.
                }
            }
        } catch (NumberFormatException ignored) {
            // An invalid default must not make the frozen JSON Schema internally inconsistent.
        }
    }

    private String jsonType(String fieldType) {
        if ("number".equalsIgnoreCase(fieldType) || "slider".equalsIgnoreCase(fieldType)) {
            return "number";
        }
        if ("integer".equalsIgnoreCase(fieldType)) {
            return "integer";
        }
        if ("boolean".equalsIgnoreCase(fieldType) || "checkbox".equalsIgnoreCase(fieldType)) {
            return "boolean";
        }
        return "string";
    }

    private boolean supportsEnum(String fieldType) {
        return "select".equalsIgnoreCase(fieldType)
                || "radio".equalsIgnoreCase(fieldType)
                || "aspect_ratio".equalsIgnoreCase(fieldType);
    }

    private JsonNode enumOptions(JsonNode options) {
        if (options == null) {
            return null;
        }
        if (options.isArray()) {
            return options;
        }
        JsonNode nested = options.get("options");
        return nested != null && nested.isArray() ? nested : null;
    }

    private boolean isBooleanModeField(ToolFieldResponse field) {
        String key = field.fieldKey() == null ? "" : field.fieldKey().trim().toLowerCase();
        return "custommode".equals(key) || "custom_mode".equals(key);
    }

    private boolean allBooleanEnumOptions(JsonNode options) {
        if (options == null || !options.isArray() || options.isEmpty()) {
            return false;
        }
        for (JsonNode option : options) {
            String value = option.isTextual()
                    ? option.asText()
                    : option.hasNonNull("value") ? option.get("value").asText() : "";
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean booleanValue(String value) {
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    private String canonicalDsl(ToolWorkflow draft) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("config", canonicalNode(readJson(draft.getConfigJson(), objectMapper.createObjectNode())));
        root.set("edges", canonicalNode(readJson(draft.getEdgesJson(), objectMapper.createArrayNode())));
        root.set("groups", canonicalNode(readJson(draft.getGroupsJson(), objectMapper.createArrayNode())));
        root.set("nodes", canonicalNode(readJson(draft.getNodesJson(), objectMapper.createArrayNode())));
        return writeJson(canonicalNode(root));
    }

    private String canonicalJson(String raw, String fallback) {
        return writeJson(canonicalNode(readJson(raw, readJson(fallback, objectMapper.createObjectNode()))));
    }

    private JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return objectMapper.nullNode();
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonicalNode(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalNode(value)));
            return array;
        }
        return node;
    }

    private JsonNode readJson(String raw, JsonNode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readTree(repairJson(raw));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工作流 JSON 格式错误");
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流版本序列化失败");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ToolWorkflow requireWorkflow(Long workflowId) {
        ToolWorkflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在: " + workflowId);
        }
        return workflow;
    }

    private BusinessException draftConflict(long latestDraftRevision) {
        return new BusinessException(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                "工作流草稿已被更新，请刷新后重试",
                Map.of("latestDraftRevision", latestDraftRevision)
        );
    }

    private WorkflowResponse toResponse(ToolWorkflow workflow) {
        if (workflow == null) {
            return null;
        }
        ToolWorkflowVersion published = workflow.getPublishedVersionId() == null
                ? null
                : versionMapper.selectById(workflow.getPublishedVersionId());
        long draftRevision = revision(workflow);
        boolean hasUnpublishedChanges = published == null
                || !Objects.equals(published.getSourceDraftRevision(), draftRevision);
        if (!hasUnpublishedChanges) {
            try {
                hasUnpublishedChanges = !Objects.equals(
                        published.getDslHash(),
                        publicationArtifacts(workflow).fingerprint()
                );
            } catch (BusinessException exception) {
                hasUnpublishedChanges = true;
            }
        }
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getToolId(),
                Utf8TextRepair.repairIfNeeded(workflow.getWorkflowName()),
                Utf8TextRepair.repairIfNeeded(workflow.getNodesJson()),
                Utf8TextRepair.repairIfNeeded(workflow.getEdgesJson()),
                Utf8TextRepair.repairIfNeeded(workflow.getGroupsJson()),
                Utf8TextRepair.repairIfNeeded(workflow.getConfigJson()),
                workflow.getVersion() == null ? 1 : workflow.getVersion(),
                workflow.getStatus(),
                draftRevision,
                workflow.getPublishedVersionId(),
                Boolean.TRUE.equals(workflow.getExecutionEnabled()),
                hasUnpublishedChanges,
                workflow.getCreatedBy(),
                workflow.getUpdatedBy(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }

    private static long revision(ToolWorkflow workflow) {
        return workflow.getDraftRevision() == null ? 0L : workflow.getDraftRevision();
    }

    private void invalidateToolCatalogAfterCommit(String toolCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bypassCacheService.invalidateToolCatalog(toolCode);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                bypassCacheService.invalidateToolCatalog(toolCode);
            }
        });
    }

    private record PublicationArtifacts(
            String canonicalDslJson,
            String inputSchemaSnapshotJson,
            String dependencyManifestJson,
            String billingPolicyJson,
            String riskPolicyJson,
            int minimumRequiredCredits,
            String fingerprint
    ) {}

    private static String repairJson(String json) {
        return Utf8TextRepair.repairIfNeeded(json);
    }
}
