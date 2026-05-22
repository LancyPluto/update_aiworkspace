package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.PptConstants;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflowStep;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;

@Service
public class PptWorkflowService {

    private final ToolMapper toolMapper;
    private final PptEngineProperties pptProperties;
    private final ObjectMapper objectMapper;

    public PptWorkflowService(ToolMapper toolMapper, PptEngineProperties pptProperties, ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.pptProperties = pptProperties;
        this.objectMapper = objectMapper;
    }

    public AiTool requireOnlinePptTool() {
        AiTool tool = toolMapper.findOnlineByCode(PptConstants.TOOL_CODE)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_OFFLINE, "PPT 工具未上线"));
        requireWorkflow(tool);
        return tool;
    }

    public PptWorkflow requireWorkflow(AiTool tool) {
        return parseWorkflow(tool.getConfigNote())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "PPT 工作流配置无效，请联系管理员"));
    }

    public Optional<PptWorkflow> parseWorkflow(String configNote) {
        if (configNote == null || configNote.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PptConstants.WORKFLOW_PATTERN.matcher(configNote);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(matcher.group(1), PptWorkflow.class));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public PptWorkflowStep requireEnabledStep(PptWorkflow workflow, String stepCode) {
        PptWorkflowStep step = workflow.getSteps().stream()
                .filter(item -> stepCode.equalsIgnoreCase(item.getCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "未知步骤: " + stepCode));
        if (!step.isEnabled()) {
            throw new BusinessException(ErrorCode.PPT_STEP_DISABLED, "该功能已关闭: " + step.getName());
        }
        return step;
    }

    public int resolveCredits(AiTool tool, PptWorkflow workflow, String stepCode) {
        return workflow.getSteps().stream()
                .filter(step -> stepCode.equalsIgnoreCase(step.getCode()))
                .map(PptWorkflowStep::getCredits)
                .filter(credits -> credits > 0)
                .findFirst()
                .orElseGet(() -> defaultCredits(stepCode));
    }

    public void validateCreationType(PptWorkflow workflow, String creationType) {
        if (creationType == null || creationType.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "creationType 不能为空");
        }
        List<String> allowed = workflow.getCreationTypes();
        if (allowed == null || allowed.isEmpty()) {
            return;
        }
        boolean matched = allowed.stream().anyMatch(type -> type.equalsIgnoreCase(creationType.trim()));
        if (!matched) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的创建方式: " + creationType);
        }
    }

    private int defaultCredits(String stepCode) {
        PptEngineProperties.Billing billing = pptProperties.getBilling();
        return switch (stepCode.toUpperCase(Locale.ROOT)) {
            case "CREATE" -> billing.getCreateProjectCredits();
            case "OUTLINE" -> billing.getGenerateOutlineCredits();
            case "DESCRIPTIONS" -> billing.getGenerateDescriptionsCredits();
            case "IMAGES" -> billing.getGenerateImagesCredits();
            case "EXPORT_PPTX" -> billing.getExportPptxCredits();
            case "EXPORT_PDF" -> billing.getExportPdfCredits();
            default -> 0;
        };
    }
}
