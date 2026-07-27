package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectMapper;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolves PPT models from the platform catalog. The PPT engine never owns a
 * second user-facing model configuration; explicit workflow bindings win and
 * missing bindings fall back to the platform's enabled defaults.
 */
@Service
public class PptPlatformModelBindingService {
    private final ToolMapper toolMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final AgentModelConfigService modelConfigService;
    private final PptWorkflowService workflowService;
    private final PptProjectMapper projectMapper;
    private final PptJobMapper jobMapper;

    public PptPlatformModelBindingService(ToolMapper toolMapper,
                                          AgentModelConfigMapper modelConfigMapper,
                                          AgentModelConfigService modelConfigService,
                                          PptWorkflowService workflowService,
                                          PptProjectMapper projectMapper,
                                          PptJobMapper jobMapper) {
        this.toolMapper = toolMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.modelConfigService = modelConfigService;
        this.workflowService = workflowService;
        this.projectMapper = projectMapper;
        this.jobMapper = jobMapper;
    }

    public ResolvedBinding resolve(PptProject project) {
        if (project == null || project.getToolId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "PPT 项目缺少平台工具绑定");
        }
        AiTool tool = toolMapper.findById(project.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "PPT 平台工具不存在"));
        PptWorkflow workflow = workflowService.parseWorkflow(tool.getConfigNote())
                .orElseGet(PptWorkflow::new);

        Long textModelConfigId = project.getTextModelConfigId() == null
                ? workflow.getTextModelConfigId()
                : project.getTextModelConfigId();
        Long imageModelConfigId = project.getImageModelConfigId() == null
                ? workflow.getImageModelConfigId()
                : project.getImageModelConfigId();

        AgentModelConfig text = textModelConfigId == null
                ? resolveDefault("TEXT_GENERATION", false)
                : requireEnabled(textModelConfigId, "文本", "TEXT_GENERATION");
        AgentModelConfig image = imageModelConfigId == null
                ? resolveDefault("IMAGE_GENERATION", true)
                : requireEnabled(imageModelConfigId, "生图", "IMAGE_GENERATION");

        workflow.setTextModelConfigId(text.getId());
        workflow.setImageModelConfigId(image.getId());
        return new ResolvedBinding(workflow, text, image);
    }

    public ModelOptions options() {
        return new ModelOptions(
                selectable("TEXT_GENERATION", false),
                selectable("IMAGE_GENERATION", true)
        );
    }

    public void validateSelection(Long textModelConfigId, Long imageModelConfigId) {
        if (textModelConfigId == null) {
            resolveDefault("TEXT_GENERATION", false);
        } else {
            requireEnabled(textModelConfigId, "文本", "TEXT_GENERATION");
        }
        if (imageModelConfigId == null) {
            resolveDefault("IMAGE_GENERATION", true);
        } else {
            requireEnabled(imageModelConfigId, "生图", "IMAGE_GENERATION");
        }
    }

    @Transactional
    public ResolvedBinding updateSelection(PptProject project,
                                           Long textModelConfigId,
                                           Long imageModelConfigId) {
        if (jobMapper.countActiveByProject(project.getId()) > 0) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "生成任务进行中，完成后再更换模型");
        }
        validateSelection(textModelConfigId, imageModelConfigId);
        if (projectMapper.updateModelSelection(
                project.getId(),
                project.getUserId(),
                textModelConfigId,
                imageModelConfigId
        ) != 1) {
            throw new BusinessException(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT 项目不存在");
        }
        project.setTextModelConfigId(textModelConfigId);
        project.setImageModelConfigId(imageModelConfigId);
        return resolve(project);
    }

    private AgentModelConfig resolveDefault(String capability, boolean preferGptImage2) {
        List<AgentModelConfig> candidates = selectable(capability, preferGptImage2);
        if (candidates.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "平台没有可用的" + ("IMAGE_GENERATION".equals(capability) ? "生图" : "文本")
                            + "模型，请先在模型管理中启用；PPT 无需单独配置"
            );
        }
        return candidates.get(0);
    }

    private List<AgentModelConfig> selectable(String capability, boolean preferGptImage2) {
        return modelConfigMapper.findAllActive().stream()
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                .filter(config -> hasCapability(config, capability))
                .map(this::resolveExecutableOrNull)
                .filter(config -> config != null)
                .filter(this::hasExecutableCredentials)
                .sorted(Comparator
                        .comparing((AgentModelConfig config) ->
                                preferGptImage2 && isGptImage2(config) ? 0 : 1)
                        .thenComparing(config -> Boolean.TRUE.equals(config.getDefault()) ? 0 : 1)
                        .thenComparing(AgentModelConfig::getId, Comparator.reverseOrder()))
                .toList();
    }

    private AgentModelConfig requireEnabled(Long id, String label, String capability) {
        AgentModelConfig config = modelConfigMapper.findActiveById(id);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型绑定不可用: " + id);
        }
        if (!hasCapability(config, capability)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型能力不匹配: " + id);
        }
        AgentModelConfig executable = modelConfigService.resolveForExecution(config);
        if (!hasExecutableCredentials(executable)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型缺少平台执行凭证: " + id);
        }
        return executable;
    }

    private AgentModelConfig resolveExecutableOrNull(AgentModelConfig config) {
        try {
            return modelConfigService.resolveForExecution(config);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasCapability(AgentModelConfig config, String capability) {
        return config.getCapabilities() != null
                && config.getCapabilities().toUpperCase(Locale.ROOT).contains(capability);
    }

    private boolean hasExecutableCredentials(AgentModelConfig config) {
        return config.getModelName() != null
                && !config.getModelName().isBlank()
                && config.getApiKey() != null
                && !config.getApiKey().isBlank();
    }

    private boolean isGptImage2(AgentModelConfig config) {
        String value = (config.getProvider() + " " + config.getModelName()).toLowerCase(Locale.ROOT);
        return value.contains("gpt-image-2")
                || value.contains("ofox_openai_images")
                || value.contains("openai_images_gateway");
    }

    public record ResolvedBinding(
            PptWorkflow workflow,
            AgentModelConfig textModel,
            AgentModelConfig imageModel
    ) {
    }

    public record ModelOptions(
            List<AgentModelConfig> textModels,
            List<AgentModelConfig> imageModels
    ) {
    }
}
