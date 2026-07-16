package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.util.Utf8TextRepair;
import com.aiminilab.aitoolmarket.tool.dto.UpsertWorkflowRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowVersionItemResponse;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class WorkflowServiceImpl implements WorkflowService {

    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";

    private final ToolWorkflowMapper workflowMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowDslService workflowDslService;
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final PricingService pricingService;

    public WorkflowServiceImpl(ToolWorkflowMapper workflowMapper,
                               ToolWorkflowVersionMapper versionMapper,
                               ObjectMapper objectMapper,
                               WorkflowDslService workflowDslService,
                               ToolMapper toolMapper,
                               AgentModelConfigMapper modelConfigMapper,
                               PricingService pricingService) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
        this.workflowDslService = workflowDslService;
        this.toolMapper = toolMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.pricingService = pricingService;
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
        long draftRevision = revision(draft);
        WorkflowDslValidationResult validation = workflowDslService.validate(draft);
        if (!validation.valid()) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "工作流校验未通过: " + String.join("; ", validation.errors())
            );
        }

        ToolWorkflowVersion currentPublished = draft.getPublishedVersionId() == null
                ? null
                : versionMapper.selectById(draft.getPublishedVersionId());
        if (currentPublished != null
                && Objects.equals(currentPublished.getSourceDraftRevision(), draftRevision)) {
            bindPublishedVersion(draft, currentPublished, operatorId);
            return toResponse(requireWorkflow(workflowId));
        }

        String canonicalDsl = canonicalDsl(draft);
        String dslHash = sha256(canonicalDsl);
        ToolWorkflowVersion identical = versionMapper.selectByWorkflowIdAndDslHash(workflowId, dslHash);
        if (identical != null && Objects.equals(identical.getSourceDraftRevision(), draftRevision)) {
            bindPublishedVersion(draft, identical, operatorId);
            return toResponse(requireWorkflow(workflowId));
        }

        ToolWorkflowVersion published = publishedSnapshot(
                draft,
                versionMapper.selectMaxVersion(workflowId) + 1,
                canonicalDsl,
                dslHash,
                operatorId
        );
        versionMapper.insert(published);
        bindPublishedVersion(draft, published, operatorId);
        return toResponse(requireWorkflow(workflowId));
    }

    @Override
    @Transactional
    public WorkflowResponse updateStatus(Long workflowId, String status, Long operatorId) {
        String normalized = normalizeStatus(status);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工作流状态不能为空");
        }
        if (PUBLISHED.equals(normalized)) {
            return publish(workflowId, operatorId);
        }
        ToolWorkflow current = requireWorkflow(workflowId);
        if (workflowMapper.disableExecution(current.getId(), operatorId) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工作流下线失败");
        }
        return toResponse(requireWorkflow(workflowId));
    }

    private void bindPublishedVersion(ToolWorkflow draft,
                                      ToolWorkflowVersion published,
                                      Long operatorId) {
        int changed = workflowMapper.bindPublishedVersion(
                draft.getId(),
                published.getId(),
                published.getVersion(),
                revision(draft),
                operatorId
        );
        if (changed != 1) {
            throw draftConflict(revision(requireWorkflow(draft.getId())));
        }
    }

    private ToolWorkflowVersion publishedSnapshot(ToolWorkflow draft,
                                                   int version,
                                                   String canonicalDsl,
                                                   String dslHash,
                                                   Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        ToolWorkflowVersion snapshot = new ToolWorkflowVersion();
        snapshot.setWorkflowId(draft.getId());
        snapshot.setVersion(version);
        snapshot.setNodesJson(repairJson(draft.getNodesJson()));
        snapshot.setEdgesJson(repairJson(draft.getEdgesJson()));
        snapshot.setGroupsJson(repairJson(draft.getGroupsJson()));
        snapshot.setConfigJson(repairJson(draft.getConfigJson()));
        snapshot.setCanonicalDslJson(canonicalDsl);
        snapshot.setDslVersion("1");
        snapshot.setNodeRegistryVersion("p0");
        snapshot.setDslHash(dslHash);
        snapshot.setInputSchemaSnapshotJson("{}");
        snapshot.setDependencyManifestJson(canonicalJson(draft.getConfigJson(), "{}"));
        snapshot.setBillingPolicyJson(billingPolicyJson(draft));
        snapshot.setRiskPolicyJson("{\"confirmationPolicy\":\"WORKFLOW_DEFINED\",\"level\":\"MEDIUM\"}");
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
        int minimum = tool == null || tool.getMinimumRequiredCredits() == null
                ? 0
                : Math.max(0, tool.getMinimumRequiredCredits());
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("mode", "WORKFLOW_STEP");
        policy.put("fallbackMaxCreditCost", Math.max(estimated, minimum));
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
            int maxCreditCost = explicitCap > 0 ? explicitCap : Math.max(estimated, minimum);
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

    private static String normalizeStatus(String status) {
        if (status == null) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!DRAFT.equals(normalized) && !PUBLISHED.equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的工作流状态: " + status);
        }
        return normalized;
    }

    private static String repairJson(String json) {
        return Utf8TextRepair.repairIfNeeded(json);
    }
}
