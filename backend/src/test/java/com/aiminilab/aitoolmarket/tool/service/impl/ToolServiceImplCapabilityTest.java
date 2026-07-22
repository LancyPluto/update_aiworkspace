package com.aiminilab.aitoolmarket.tool.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderMetadataService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.support.GeneratedMediaPathSupport;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.dto.ApplyToolTemplateRequest;
import com.aiminilab.aitoolmarket.tool.dto.WorkflowResponse;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationRegistry;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationResolver;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldSchemaMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolTemplateService;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolServiceImplCapabilityTest {

    private final ToolMapper toolMapper = mock(ToolMapper.class);
    private final ToolFieldSchemaMapper toolFieldSchemaMapper = mock(ToolFieldSchemaMapper.class);
    private final ToolFieldItemMapper toolFieldItemMapper = mock(ToolFieldItemMapper.class);
    private final AgentModelConfigMapper agentModelConfigMapper = mock(AgentModelConfigMapper.class);
    private final ModelProviderMetadataService metadataService = mock(ModelProviderMetadataService.class);
    private final ModelConfigCredentialResolver credentialResolver = mock(ModelConfigCredentialResolver.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ToolTemplateService toolTemplateService = mock(ToolTemplateService.class);
    private ToolServiceImpl toolService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        when(metadataService.list(null)).thenReturn(List.of(provider(
                List.of("TEXT_GENERATION", "VISION_INPUT", "IMAGE_GENERATION", "VIDEO_GENERATION")
        )));
        when(metadataService.get(anyString())).thenReturn(provider(
                List.of("TEXT_GENERATION", "VISION_INPUT", "IMAGE_GENERATION", "VIDEO_GENERATION")
        ));
        when(credentialResolver.resolveForExecution(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ModelCapabilityService capabilityService = new ModelCapabilityService(
                new ModelProviderRegistry(),
                metadataService,
                new ModelCapabilitiesCodec(objectMapper),
                agentModelConfigMapper,
                credentialResolver
        );
        toolService = new ToolServiceImpl(
                toolMapper,
                mock(ToolCategoryMapper.class),
                toolFieldSchemaMapper,
                toolFieldItemMapper,
                mock(ToolPromptMapper.class),
                mock(ToolPromptVersionMapper.class),
                objectMapper,
                toolTemplateService,
                capabilityService,
                mock(TaskCreditEstimateService.class),
                mock(AssetStorageService.class),
                mock(GeneratedMediaPathSupport.class),
                mock(ToolIntegrationResolver.class),
                mock(ToolIntegrationRegistry.class),
                mock(BypassCacheService.class),
                mock(WorkflowExecutionService.class),
                workflowService
        );
    }

    @Test
    void createTool_persistsNormalizedRequiredModelCapabilitiesAndReturnsThem() {
        AtomicReference<AiTool> inserted = prepareSuccessfulInsert();
        UpsertToolRequest request = request(
                "IMAGE_GENERATION",
                List.of(" image_generation ", "VIDEO_GENERATION", "IMAGE_GENERATION")
        );

        ToolSummaryResponse response = toolService.createTool(request, 7L);

        assertThat(inserted.get().getRequiredModelCapabilities())
                .isEqualTo("[\"IMAGE_GENERATION\",\"VIDEO_GENERATION\"]");
        assertThat(response.requiredModelCapabilities())
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void createTool_rejectsExplicitEmptyRequiredModelCapabilities() {
        UpsertToolRequest request = request("IMAGE_GENERATION", List.of());

        assertThatThrownBy(() -> toolService.createTool(request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be empty");
        verify(toolMapper, never()).insertTool(any(), anyLong());
    }

    @Test
    void createTool_rejectsExplicitLegacyDigitalHumanCapability() {
        UpsertToolRequest request = request("DIGITAL_HUMAN", List.of("DIGITAL_HUMAN"));

        assertThatThrownBy(() -> toolService.createTool(request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported model capability: DIGITAL_HUMAN");
        verify(toolMapper, never()).insertTool(any(), anyLong());
    }

    @Test
    void createTool_mapsLegacyDigitalHumanHandlerToVideoCapabilityWhenFieldIsOmitted() {
        AtomicReference<AiTool> inserted = prepareSuccessfulInsert();
        UpsertToolRequest request = request("DIGITAL_HUMAN", null);

        toolService.createTool(request, 7L);

        assertThat(inserted.get().getRequiredModelCapabilities()).isEqualTo("[\"VIDEO_GENERATION\"]");
    }

    @Test
    void createTool_derivesImageUnderstandingCapabilitiesBeforeDefaultingExecutionHandler() {
        AtomicReference<AiTool> inserted = prepareSuccessfulInsert();
        UpsertToolRequest request = requestForType("IMAGE_UNDERSTANDING", null, null);

        toolService.createTool(request, 7L);

        assertThat(inserted.get().getExecutionHandler()).isEqualTo("TEXT_GENERATION");
        assertThat(inserted.get().getRequiredModelCapabilities())
                .isEqualTo("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
    }

    @Test
    void createTool_validatesBindingAfterApplyingTemplateDefaults() {
        AtomicReference<AiTool> inserted = prepareSuccessfulInsert();
        AgentModelConfig seedance = model(44L, "[\"VIDEO_GENERATION\"]");
        seedance.setProvider("seedance");
        when(agentModelConfigMapper.findActiveById(44L)).thenReturn(seedance);
        doAnswer(invocation -> {
            AiTool persisted = inserted.get();
            persisted.setToolType("AGENT");
            persisted.setExecutionHandler("DIGITAL_HUMAN");
            return null;
        }).when(toolTemplateService).applyToTool(
                eq(10L), any(ApplyToolTemplateRequest.class), eq(7L));
        UpsertToolRequest request = new UpsertToolRequest(
                "capability_tool",
                "Capability tool",
                1L,
                "test",
                null,
                "AGENT",
                "TEXT",
                "VIDEO",
                null,
                0,
                44L,
                null,
                null,
                "digital_human_default"
        );

        ToolSummaryResponse response = toolService.createTool(request, 7L);

        assertThat(inserted.get().getRequiredModelCapabilities()).isEqualTo("[\"VIDEO_GENERATION\"]");
        assertThat(response.requiredModelCapabilities()).containsExactly("VIDEO_GENERATION");
    }

    @Test
    void updateTool_rejectsBoundModelMissingAnySelectedCapability() {
        AiTool existing = tool(10L, "DRAFT", null, "[\"TEXT_GENERATION\"]");
        when(toolMapper.findById(10L)).thenReturn(Optional.of(existing));
        AgentModelConfig textOnly = model(44L, "[\"TEXT_GENERATION\"]");
        when(agentModelConfigMapper.findActiveById(44L)).thenReturn(textOnly);
        UpsertToolRequest request = request(
                "TEXT_GENERATION",
                List.of("TEXT_GENERATION", "VISION_INPUT"),
                44L
        );

        assertThatThrownBy(() -> toolService.updateTool(10L, request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");
        verify(toolMapper, never()).updateTool(eq(10L), any(), eq(7L));
    }

    @Test
    void updateWorkflowTool_rejectsBoundModelMissingAnySelectedCapability() {
        AiTool existing = tool(10L, "DRAFT", null, "[\"TEXT_GENERATION\"]");
        when(toolMapper.findById(10L)).thenReturn(Optional.of(existing));
        when(workflowService.getWorkflow(10L)).thenReturn(workflow(10L));
        AgentModelConfig textOnly = model(44L, "[\"TEXT_GENERATION\"]");
        when(agentModelConfigMapper.findActiveById(44L)).thenReturn(textOnly);
        UpsertToolRequest request = request(
                "TEXT_GENERATION",
                List.of("TEXT_GENERATION", "VISION_INPUT"),
                44L
        );

        assertThatThrownBy(() -> toolService.updateTool(10L, request, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");
        verify(toolMapper, never()).updateTool(eq(10L), any(), eq(7L));
    }

    @Test
    void applyTemplateToWorkflowTool_rejectsIncompatibleExplicitModelBinding() {
        AiTool persisted = tool(10L, "DRAFT", 44L, "[\"TEXT_GENERATION\"]");
        persisted.setToolType("VIDEO_GENERATION");
        persisted.setExecutionHandler("VIDEO_GENERATION");
        when(toolMapper.findById(10L)).thenReturn(Optional.of(persisted));
        when(workflowService.getWorkflow(10L)).thenReturn(workflow(10L));
        when(agentModelConfigMapper.findActiveById(44L))
                .thenReturn(model(44L, "[\"TEXT_GENERATION\"]"));

        assertThatThrownBy(() -> toolService.applyTemplate(
                10L,
                new ApplyToolTemplateRequest("video_generation_default", true, true),
                7L
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VIDEO_GENERATION");
    }

    @Test
    void publishTool_rejectsUnboundToolWhenNoModelSupportsAllCapabilities() {
        AiTool tool = tool(
                10L,
                "DRAFT",
                null,
                "[\"TEXT_GENERATION\",\"VISION_INPUT\"]"
        );
        when(toolMapper.findByIdForUpdate(10L)).thenReturn(Optional.of(tool));
        when(agentModelConfigMapper.findAllActive()).thenReturn(List.of(model(11L, "[\"TEXT_GENERATION\"]")));

        assertThatThrownBy(() -> toolService.publishTool(10L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no enabled model config supports");
        verify(toolMapper, never()).updateToolStatus(anyLong(), any(), anyLong());
    }

    @Test
    void publishWorkflowTool_rejectsExplicitModelMissingAnySelectedCapability() {
        AiTool tool = tool(
                10L,
                "DRAFT",
                44L,
                "[\"TEXT_GENERATION\",\"VISION_INPUT\"]"
        );
        when(toolMapper.findByIdForUpdate(10L)).thenReturn(Optional.of(tool));
        when(workflowService.getWorkflow(10L)).thenReturn(workflow(10L));
        when(agentModelConfigMapper.findActiveById(44L))
                .thenReturn(model(44L, "[\"TEXT_GENERATION\"]"));

        assertThatThrownBy(() -> toolService.publishTool(10L, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");
        verify(toolMapper, never()).activateWorkflowTool(anyLong(), anyLong());
    }

    private AtomicReference<AiTool> prepareSuccessfulInsert() {
        AtomicReference<AiTool> inserted = new AtomicReference<>();
        when(toolMapper.insertTool(any(AiTool.class), anyLong())).thenAnswer(invocation -> {
            AiTool tool = invocation.getArgument(0);
            tool.setId(10L);
            inserted.set(tool);
            return 10L;
        });
        when(toolFieldSchemaMapper.findActiveSchemaId(10L)).thenReturn(Optional.of(20L));
        when(toolMapper.findById(10L)).thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
        return inserted;
    }

    private static UpsertToolRequest request(String executionHandler, List<String> capabilities) {
        return request(executionHandler, capabilities, null);
    }

    private static UpsertToolRequest request(String executionHandler,
                                             List<String> capabilities,
                                             Long modelConfigId) {
        return requestForType("VIDEO_GENERATION", executionHandler, capabilities, modelConfigId);
    }

    private static UpsertToolRequest requestForType(String toolType,
                                                    String executionHandler,
                                                    List<String> capabilities) {
        return requestForType(toolType, executionHandler, capabilities, null);
    }

    private static UpsertToolRequest requestForType(String toolType,
                                                    String executionHandler,
                                                    List<String> capabilities,
                                                    Long modelConfigId) {
        return new UpsertToolRequest(
                "capability_tool",
                "Capability tool",
                1L,
                "test",
                null,
                toolType,
                "TEXT",
                "VIDEO",
                null,
                0,
                modelConfigId,
                executionHandler,
                capabilities,
                null
        );
    }

    private static AiTool tool(Long id, String status, Long modelConfigId, String capabilities) {
        AiTool tool = new AiTool();
        tool.setId(id);
        tool.setToolCode("capability_tool");
        tool.setToolName("Capability tool");
        tool.setToolType("TEXT_GENERATION");
        tool.setExecutionHandler("TEXT_GENERATION");
        tool.setStatus(status);
        tool.setModelConfigId(modelConfigId);
        tool.setRequiredModelCapabilities(capabilities);
        return tool;
    }

    private static AgentModelConfig model(Long id, String capabilities) {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setConfigCode("model_" + id);
        config.setProvider("mock");
        config.setModelName("model-" + id);
        config.setCapabilities(capabilities);
        config.setEnabled(true);
        return config;
    }

    private static ModelProviderResponse provider(List<String> capabilities) {
        return new ModelProviderResponse(
                "test_provider",
                "Test provider",
                capabilities,
                "",
                "",
                "PER_CALL",
                "test",
                "direct",
                "test",
                "accept_only",
                true,
                true,
                "",
                "test",
                null,
                null,
                ""
        );
    }

    private static WorkflowResponse workflow(Long toolId) {
        return new WorkflowResponse(
                90L,
                toolId,
                "default",
                "[]",
                "[]",
                "[]",
                "{}",
                1,
                "DRAFT",
                1L,
                null,
                false,
                true,
                7L,
                7L,
                null,
                null
        );
    }
}
