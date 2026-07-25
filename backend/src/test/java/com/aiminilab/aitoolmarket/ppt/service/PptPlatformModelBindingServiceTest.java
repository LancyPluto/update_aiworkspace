package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.entity.PptProject;
import com.aiminilab.aitoolmarket.ppt.mapper.PptJobMapper;
import com.aiminilab.aitoolmarket.ppt.mapper.PptProjectMapper;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class PptPlatformModelBindingServiceTest {
    @Mock private ToolMapper toolMapper;
    @Mock private AgentModelConfigMapper modelConfigMapper;
    @Mock private AgentModelConfigService modelConfigService;
    @Mock private PptWorkflowService workflowService;
    @Mock private PptProjectMapper projectMapper;
    @Mock private PptJobMapper jobMapper;

    private PptPlatformModelBindingService service;
    private PptProject project;
    private AiTool tool;

    @BeforeEach
    void setUp() {
        service = new PptPlatformModelBindingService(
                toolMapper, modelConfigMapper, modelConfigService, workflowService,
                projectMapper, jobMapper);
        project = new PptProject();
        project.setId(5L);
        project.setUserId(3L);
        project.setToolId(7L);
        tool = new AiTool();
        tool.setId(7L);
        tool.setConfigNote("<!-- ppt-workflow:{} -->");
        lenient().when(toolMapper.findById(7L)).thenReturn(Optional.of(tool));
        lenient().when(modelConfigService.resolveForExecution(any(AgentModelConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void zeroConfigResolutionReusesDefaultTextAndPrefersPlatformGptImage2() {
        PptWorkflow workflow = new PptWorkflow();
        AgentModelConfig text = model(10L, "deepseek", "deepseek-chat", "TEXT_GENERATION", true);
        AgentModelConfig ordinaryImage = model(11L, "siliconflow_images", "flux", "IMAGE_GENERATION", true);
        AgentModelConfig gptImage2 = model(
                12L, "ofox_openai_images", "openai/gpt-image-2", "IMAGE_GENERATION", false);
        when(workflowService.parseWorkflow(tool.getConfigNote())).thenReturn(Optional.of(workflow));
        when(modelConfigMapper.findAllActive()).thenReturn(List.of(text, ordinaryImage, gptImage2));

        PptPlatformModelBindingService.ResolvedBinding resolved = service.resolve(project);

        assertEquals(10L, resolved.textModel().getId());
        assertEquals(12L, resolved.imageModel().getId());
        assertEquals(10L, resolved.workflow().getTextModelConfigId());
        assertEquals(12L, resolved.workflow().getImageModelConfigId());
    }

    @Test
    void explicitWorkflowBindingResolvesModelsWithoutSynchronizingProviderSecrets() {
        PptWorkflow workflow = new PptWorkflow();
        AgentModelConfig text = model(10L, "deepseek", "deepseek-chat", "TEXT_GENERATION", true);
        AgentModelConfig image = model(
                12L, "ofox_openai_images", "openai/gpt-image-2", "IMAGE_GENERATION", true);
        workflow.setTextModelConfigId(10L);
        workflow.setImageModelConfigId(12L);
        when(workflowService.parseWorkflow(tool.getConfigNote())).thenReturn(Optional.of(workflow));
        when(modelConfigMapper.findActiveById(10L)).thenReturn(text);
        when(modelConfigMapper.findActiveById(12L)).thenReturn(image);

        PptPlatformModelBindingService.ResolvedBinding resolved = service.resolve(project);

        assertEquals("deepseek-chat", resolved.textModel().getModelName());
        assertEquals("openai/gpt-image-2", resolved.imageModel().getModelName());
    }

    @Test
    void projectSelectionOverridesToolWorkflowBinding() {
        PptWorkflow workflow = new PptWorkflow();
        workflow.setTextModelConfigId(20L);
        workflow.setImageModelConfigId(21L);
        project.setTextModelConfigId(10L);
        project.setImageModelConfigId(12L);
        AgentModelConfig text = model(10L, "qwen", "qwen-plus", "TEXT_GENERATION", false);
        AgentModelConfig image = model(12L, "openai", "gpt-image-2", "IMAGE_GENERATION", false);
        when(workflowService.parseWorkflow(tool.getConfigNote())).thenReturn(Optional.of(workflow));
        when(modelConfigMapper.findActiveById(10L)).thenReturn(text);
        when(modelConfigMapper.findActiveById(12L)).thenReturn(image);

        PptPlatformModelBindingService.ResolvedBinding resolved = service.resolve(project);

        assertEquals(10L, resolved.textModel().getId());
        assertEquals(12L, resolved.imageModel().getId());
    }

    @Test
    void modelSelectionCannotChangeWhileProjectJobIsActive() {
        when(jobMapper.countActiveByProject(5L)).thenReturn(1L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateSelection(project, 10L, 12L)
        );

        assertEquals("生成任务进行中，完成后再更换模型", exception.getMessage());
    }

    private AgentModelConfig model(Long id,
                                   String provider,
                                   String name,
                                   String capabilities,
                                   boolean isDefault) {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setProvider(provider);
        config.setModelName(name);
        config.setCapabilities("[\"" + capabilities + "\"]");
        config.setApiKey("platform-managed-secret");
        config.setEnabled(true);
        config.setDefault(isDefault);
        return config;
    }
}
