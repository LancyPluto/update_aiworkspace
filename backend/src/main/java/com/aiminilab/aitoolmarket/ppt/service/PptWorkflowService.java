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
import java.util.Set;
import java.util.regex.Matcher;

@Service
public class PptWorkflowService {

    /** 平台内置步骤；管理端 configNote 未列出时仍允许调用（使用默认算力）。 */
    private static final Set<String> BUILTIN_STEP_CODES = Set.of(
            "CREATE",
            "OUTLINE",
            "DESCRIPTIONS",
            "IMAGES",
            "EXPORT_PPTX",
            "EXPORT_PDF",
            "EXPORT_EDITABLE_PPTX"
    );

    private final ToolMapper toolMapper;
    private final PptEngineProperties pptProperties;
    private final ObjectMapper objectMapper;

    public PptWorkflowService(ToolMapper toolMapper, PptEngineProperties pptProperties, ObjectMapper objectMapper) {
        this.toolMapper = toolMapper;
        this.pptProperties = pptProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * @deprecated 全站只允许一个 PPT 工具的写法。新代码请用
     * {@link #requirePptWorkspaceTool(String)} 或
     * {@link #requirePptWorkspaceToolById(Long)}；本方法仅作为缺 toolCode 时的过渡兜底。
     */
    @Deprecated
    public AiTool requireOnlinePptTool() {
        AiTool tool = toolMapper.findOnlineByCode(PptConstants.TOOL_CODE)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_OFFLINE, "PPT 工具未上线"));
        requireWorkflow(tool);
        return tool;
    }

    /**
     * 按 toolCode 加载在线的 PPT 工作台工具。
     */
    public AiTool requirePptWorkspaceTool(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "toolCode 不能为空");
        }
        AiTool tool = toolMapper.findOnlineByCode(toolCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_OFFLINE, "PPT 工具未上线: " + toolCode));
        ensurePptWorkspace(tool);
        return tool;
    }

    /**
     * 按 toolId 加载 PPT 工作台工具（绑定表反查路径使用，未限制 status）。
     */
    public AiTool requirePptWorkspaceToolById(Long toolId) {
        if (toolId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "toolId 不能为空");
        }
        AiTool tool = toolMapper.findById(toolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "PPT 工具不存在: " + toolId));
        ensurePptWorkspace(tool);
        return tool;
    }

    public PptWorkflow requireWorkflow(AiTool tool) {
        return parseWorkflow(tool.getConfigNote())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "PPT 工作流配置无效，请联系管理员"));
    }

    /**
     * 判定工具是否声明为 PPT 工作台（兼容旧版 ppt-workflow 块：解析成功即视为
     * {@code PPT_WORKSPACE}）。
     */
    public boolean isPptWorkspace(AiTool tool) {
        if (tool == null) {
            return false;
        }
        return parseWorkflow(tool.getConfigNote())
                .map(workflow -> workflow.getIntegrationMode() == null
                        || workflow.getIntegrationMode().isBlank()
                        || PptConstants.INTEGRATION_MODE.equalsIgnoreCase(workflow.getIntegrationMode()))
                .orElse(false);
    }

    private void ensurePptWorkspace(AiTool tool) {
        if (!isPptWorkspace(tool)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "工具未配置 PPT 工作台: " + tool.getToolCode());
        }
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
        String normalized = stepCode == null ? "" : stepCode.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "步骤编码不能为空");
        }
        if (workflow.getSteps() != null) {
            for (PptWorkflowStep item : workflow.getSteps()) {
                if (item.getCode() != null && normalized.equalsIgnoreCase(item.getCode().trim())) {
                    if (!item.isEnabled()) {
                        throw new BusinessException(ErrorCode.PPT_STEP_DISABLED, "该功能已关闭: " + item.getName());
                    }
                    return item;
                }
            }
        }
        if (BUILTIN_STEP_CODES.contains(normalized)) {
            return builtinStep(normalized);
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "未知步骤: " + stepCode);
    }

    private PptWorkflowStep builtinStep(String stepCode) {
        PptWorkflowStep step = new PptWorkflowStep();
        step.setCode(stepCode);
        step.setName(builtinStepName(stepCode));
        step.setCredits(defaultCredits(stepCode));
        step.setEnabled(true);
        return step;
    }

    private String builtinStepName(String stepCode) {
        return switch (stepCode) {
            case "CREATE" -> "创建项目";
            case "OUTLINE" -> "生成大纲";
            case "DESCRIPTIONS" -> "生成描述";
            case "IMAGES" -> "生成图片";
            case "EXPORT_PPTX" -> "导出图片幻灯片";
            case "EXPORT_PDF" -> "导出 PDF";
            case "EXPORT_EDITABLE_PPTX" -> "导出可编辑 PPTX";
            default -> stepCode;
        };
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
        String normalized = creationType.trim();
        boolean matched = allowed.stream().anyMatch(type -> matchesCreationType(type, normalized));
        if (!matched) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的创建方式: " + creationType);
        }
    }

    private static boolean matchesCreationType(String allowed, String actual) {
        if (allowed.equalsIgnoreCase(actual)) {
            return true;
        }
        boolean allowedDescription = "description".equalsIgnoreCase(allowed) || "descriptions".equalsIgnoreCase(allowed);
        boolean actualDescription = "description".equalsIgnoreCase(actual) || "descriptions".equalsIgnoreCase(actual);
        return allowedDescription && actualDescription;
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
            case "EXPORT_EDITABLE_PPTX" -> billing.getExportEditablePptxCredits();
            default -> 0;
        };
    }
}
