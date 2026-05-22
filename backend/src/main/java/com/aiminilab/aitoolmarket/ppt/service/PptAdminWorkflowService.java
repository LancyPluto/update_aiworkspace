package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.PptConstants;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflowStep;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;

@Service
public class PptAdminWorkflowService {

    private final ToolMapper toolMapper;
    private final PptWorkflowService pptWorkflowService;
    private final ObjectMapper objectMapper;

    public PptAdminWorkflowService(ToolMapper toolMapper,
                                   PptWorkflowService pptWorkflowService,
                                   ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.pptWorkflowService = pptWorkflowService;
        this.objectMapper = objectMapper;
    }

    public PptWorkflow getWorkflow(Long toolId) {
        AiTool tool = requirePptTool(toolId);
        return pptWorkflowService.requireWorkflow(tool);
    }

    @Transactional
    public PptWorkflow updateWorkflow(Long toolId, PptWorkflow workflow, Long operatorId) {
        AiTool tool = requirePptTool(toolId);
        validateWorkflow(workflow);
        tool.setConfigNote(mergeWorkflowIntoConfigNote(tool.getConfigNote(), workflow));
        toolMapper.updateTool(toolId, tool, operatorId);
        return workflow;
    }

    private AiTool requirePptTool(Long toolId) {
        AiTool tool = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        if (!PptConstants.TOOL_CODE.equals(tool.getToolCode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅 PPT 工具支持工作流配置");
        }
        return tool;
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
