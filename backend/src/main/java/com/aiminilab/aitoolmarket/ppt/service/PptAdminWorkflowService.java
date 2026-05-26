package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.PptConstants;
import com.aiminilab.aitoolmarket.ppt.dto.PptAdminWorkflowDetailResponse;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflowStep;
import com.aiminilab.aitoolmarket.tool.integration.api.ToolIntegrationApiCatalog;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

@Service
public class PptAdminWorkflowService {

    private final ToolMapper toolMapper;
    private final PptWorkflowService pptWorkflowService;
    private final PptEngineSettingsSyncService pptEngineSettingsSyncService;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ObjectMapper objectMapper;

    public PptAdminWorkflowService(ToolMapper toolMapper,
                                   PptWorkflowService pptWorkflowService,
                                   PptEngineSettingsSyncService pptEngineSettingsSyncService,
                                   AgentModelConfigMapper agentModelConfigMapper,
                                   ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.pptWorkflowService = pptWorkflowService;
        this.pptEngineSettingsSyncService = pptEngineSettingsSyncService;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.objectMapper = objectMapper;
    }

    public PptAdminWorkflowDetailResponse getWorkflowDetail(Long toolId) {
        AiTool tool = requirePptTool(toolId);
        PptWorkflow workflow = pptWorkflowService.parseWorkflow(tool.getConfigNote())
                .orElseGet(this::defaultWorkflowSkeleton);
        return toDetailResponse(workflow, false, null);
    }

    @Transactional
    public PptAdminWorkflowDetailResponse updateWorkflow(Long toolId, PptWorkflow workflow, Long operatorId) {
        AiTool tool = requirePptTool(toolId);
        PptWorkflow previous = pptWorkflowService.parseWorkflow(tool.getConfigNote()).orElse(null);
        Map<String, String> previousSecrets = previous == null ? Map.of() : previous.getEngineSecrets();
        workflow.setEngineSecrets(PptEngineSecretSupport.mergeIncomingSecrets(workflow.getEngineSecrets(), previousSecrets));
        validateWorkflow(workflow);
        tool.setConfigNote(mergeWorkflowIntoConfigNote(tool.getConfigNote(), workflow));
        toolMapper.updateTool(toolId, tool, operatorId);
        return syncAndDetail(workflow);
    }

    public PptAdminWorkflowDetailResponse syncEngineSettings(Long toolId) {
        AiTool tool = requirePptTool(toolId);
        PptWorkflow workflow = pptWorkflowService.requireWorkflow(tool);
        return syncAndDetail(workflow);
    }

    private PptAdminWorkflowDetailResponse syncAndDetail(PptWorkflow workflow) {
        try {
            pptEngineSettingsSyncService.syncFromWorkflow(workflow);
            return toDetailResponse(workflow, true, "已同步到 PPT 引擎");
        } catch (BusinessException exception) {
            return toDetailResponse(workflow, false, exception.getMessage());
        } catch (RuntimeException exception) {
            return toDetailResponse(workflow, false, "引擎同步失败: " + exception.getMessage());
        }
    }

    private PptAdminWorkflowDetailResponse toDetailResponse(PptWorkflow workflow,
                                                            boolean engineSynced,
                                                            String engineSyncMessage) {
        workflow.setEngineSecrets(PptEngineSecretSupport.normalizeSecrets(workflow.getEngineSecrets()));
        return new PptAdminWorkflowDetailResponse(
                workflow,
                ToolIntegrationApiCatalog.require(ToolIntegrationApiCatalog.PPT_PLUGIN),
                resolveModelSummary(workflow.getTextModelConfigId()),
                resolveModelSummary(workflow.getImageModelConfigId()),
                PptEngineSecretSupport.toViews(workflow.getEngineSecrets()),
                engineSynced,
                engineSyncMessage
        );
    }

    private AgentModelConfigResponse resolveModelSummary(Long configId) {
        if (configId == null) {
            return null;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(configId);
        return config == null ? null : AgentModelConfigResponse.from(config);
    }

    private AiTool requirePptTool(Long toolId) {
        AiTool tool = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        // 允许首次保存时 config_note 还没有 ppt-workflow 块（前端会在 updateWorkflow 里写入），
        // 仅当工具已有内容但不是 PPT 工作台模式时拒绝（防止误把标准任务工具改成 PPT）。
        if (tool.getConfigNote() != null
                && !tool.getConfigNote().isBlank()
                && pptWorkflowService.parseWorkflow(tool.getConfigNote()).isPresent()
                && !pptWorkflowService.isPptWorkspace(tool)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该工具的集成模式不是 PPT 工作台");
        }
        return tool;
    }

    private PptWorkflow defaultWorkflowSkeleton() {
        PptWorkflow workflow = new PptWorkflow();
        workflow.setIntegrationMode(PptConstants.INTEGRATION_MODE);
        workflow.setCustomUiRoute("/tools/" + PptConstants.TOOL_CODE + "/workspace");
        workflow.setCreationTypes(List.of("idea", "outline", "descriptions"));
        List<PptWorkflowStep> steps = new ArrayList<>();
        steps.add(step("CREATE", "创建项目", 5));
        steps.add(step("OUTLINE", "生成大纲", 10));
        steps.add(step("DESCRIPTIONS", "生成描述", 20));
        steps.add(step("IMAGES", "生成图片", 50));
        steps.add(step("EXPORT_PPTX", "导出图片幻灯片", 5));
        workflow.setSteps(steps);
        return workflow;
    }

    private static PptWorkflowStep step(String code, String name, int credits) {
        PptWorkflowStep step = new PptWorkflowStep();
        step.setCode(code);
        step.setName(name);
        step.setCredits(credits);
        step.setEnabled(true);
        return step;
    }

    void validateWorkflow(PptWorkflow workflow) {
        if (workflow == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "workflow 不能为空");
        }
        if (workflow.getCreationTypes() == null || workflow.getCreationTypes().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "creationTypes 不能为空");
        }
        List<PptWorkflowStep> steps = workflow.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "steps 不能为空");
        }
        for (PptWorkflowStep step : steps) {
            if (step.getCode() == null || step.getCode().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "步骤 code 不能为空");
            }
            if (step.getName() == null || step.getName().isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "步骤 name 不能为空");
            }
        }
        if (workflow.getIntegrationMode() == null || workflow.getIntegrationMode().isBlank()) {
            workflow.setIntegrationMode(PptConstants.INTEGRATION_MODE);
        }
        workflow.setEngineSecrets(PptEngineSecretSupport.normalizeSecrets(workflow.getEngineSecrets()));
        boolean hasModel = workflow.getTextModelConfigId() != null || workflow.getImageModelConfigId() != null;
        boolean hasSecret = workflow.getEngineSecrets() != null
                && workflow.getEngineSecrets().values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!hasModel && !hasSecret) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请至少配置一项大模型绑定或引擎 API");
        }
    }

    String mergeWorkflowIntoConfigNote(String configNote, PptWorkflow workflow) {
        try {
            String json = objectMapper.writeValueAsString(workflow);
            String marker = "<!-- ppt-workflow:" + json + " -->";
            if (configNote == null || configNote.isBlank()) {
                return marker;
            }
            Matcher matcher = PptConstants.WORKFLOW_PATTERN.matcher(configNote);
            if (matcher.find()) {
                return matcher.replaceFirst(Matcher.quoteReplacement(marker));
            }
            return configNote.strip() + "\n" + marker;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "工作流 JSON 序列化失败");
        }
    }
}
