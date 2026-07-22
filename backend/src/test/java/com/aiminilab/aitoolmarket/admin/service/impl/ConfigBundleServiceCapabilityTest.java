package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.ModelAccountRoutingService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountService;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
import com.aiminilab.aitoolmarket.tool.dto.ToolCategoryResponse;
import com.aiminilab.aitoolmarket.tool.dto.ToolSummaryResponse;
import com.aiminilab.aitoolmarket.tool.dto.UpsertToolRequest;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolCategory;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldSchemaMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolPromptVersionMapper;
import com.aiminilab.aitoolmarket.tool.service.ToolService;
import com.aiminilab.aitoolmarket.tool.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigBundleServiceCapabilityTest {

    private final SystemSettingService systemSettingService = mock(SystemSettingService.class);
    private final AgentModelConfigService agentModelConfigService = mock(AgentModelConfigService.class);
    private final AgentModelConfigMapper agentModelConfigMapper = mock(AgentModelConfigMapper.class);
    private final AgentToolDescriptorExtensionMapper extensionMapper = mock(AgentToolDescriptorExtensionMapper.class);
    private final ToolService toolService = mock(ToolService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final ToolMapper toolMapper = mock(ToolMapper.class);
    private final ToolFieldSchemaMapper fieldSchemaMapper = mock(ToolFieldSchemaMapper.class);
    private final ToolCategoryMapper categoryMapper = mock(ToolCategoryMapper.class);
    private final ModelVendorAccountMapper vendorAccountMapper = mock(ModelVendorAccountMapper.class);
    private final ModelCapabilityService modelCapabilityService = mock(ModelCapabilityService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private ConfigBundleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConfigBundleServiceImpl(
                systemSettingService,
                agentModelConfigService,
                agentModelConfigMapper,
                extensionMapper,
                toolService,
                workflowService,
                toolMapper,
                fieldSchemaMapper,
                categoryMapper,
                mock(ToolPromptVersionMapper.class),
                new ModelProviderRegistry(),
                vendorAccountMapper,
                mock(ModelVendorAccountService.class),
                mock(ModelAccountRoutingService.class),
                modelCapabilityService,
                mock(BypassCacheService.class),
                transactionTemplate,
                mock(PricingRuleMapper.class)
        );
        when(agentModelConfigService.adminList()).thenReturn(List.of());
        when(vendorAccountMapper.findAllActive()).thenReturn(List.of());
        when(extensionMapper.findByToolCode(any())).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(toolMapper.selectList(any())).thenReturn(List.of());
        when(toolMapper.findAnyByCode(any())).thenReturn(Optional.empty());
    }

    @Test
    void selectedToolBundle_roundTripsRequiredModelCapabilitiesIntoUpsertRequest() {
        ToolCategoryResponse category = new ToolCategoryResponse(3L, "media", "Media", 0, "ACTIVE");
        when(toolService.adminCategories()).thenReturn(List.of(category));
        ToolSummaryResponse summary = summary(List.of("IMAGE_GENERATION", "VIDEO_GENERATION"));
        when(toolService.adminTools(null, null, null, 1, 200))
                .thenReturn(new PageResponse<>(List.of(summary), 1, 1, 200, false));
        when(toolService.adminFields(10L)).thenReturn(List.of());
        when(toolService.prompts(10L)).thenReturn(List.of());

        ConfigBundleDto exported = service.exportBundle(7L, false, List.of("capability_tool"), true);

        assertThat(exported.tools()).singleElement()
                .extracting(ConfigBundleDto.Tool::requiredModelCapabilities)
                .isEqualTo(List.of("IMAGE_GENERATION", "VIDEO_GENERATION"));

        ToolCategory categoryEntity = new ToolCategory();
        categoryEntity.setId(3L);
        categoryEntity.setCategoryCode("media");
        when(categoryMapper.selectList(any())).thenReturn(List.of(categoryEntity));
        when(toolService.createTool(any(), eq(7L))).thenReturn(summary);
        ArgumentCaptor<UpsertToolRequest> requestCaptor = ArgumentCaptor.forClass(UpsertToolRequest.class);

        service.importBundle(exported, 7L);

        verify(toolService).createTool(requestCaptor.capture(), eq(7L));
        assertThat(requestCaptor.getValue().requiredModelCapabilities())
                .containsExactly("IMAGE_GENERATION", "VIDEO_GENERATION");
    }

    @Test
    void import_doesNotRestoreDisabledModelBindingWhenCapabilitiesDoNotMatch() {
        ToolCategoryResponse category = new ToolCategoryResponse(3L, "media", "Media", 0, "ACTIVE");
        when(toolService.adminCategories()).thenReturn(List.of(category));
        ToolCategory categoryEntity = new ToolCategory();
        categoryEntity.setId(3L);
        categoryEntity.setCategoryCode("media");
        when(categoryMapper.selectList(any())).thenReturn(List.of(categoryEntity));

        AgentModelConfigResponse modelResponse = mock(AgentModelConfigResponse.class);
        when(modelResponse.id()).thenReturn(44L);
        when(modelResponse.configCode()).thenReturn("disabled_text");
        when(agentModelConfigService.adminList()).thenReturn(List.of(modelResponse));
        AgentModelConfig disabledModel = new AgentModelConfig();
        disabledModel.setId(44L);
        disabledModel.setEnabled(false);
        disabledModel.setCapabilities("[\"TEXT_GENERATION\"]");
        when(agentModelConfigMapper.findActiveById(44L)).thenReturn(disabledModel);

        ToolSummaryResponse saved = summary(List.of("TEXT_GENERATION", "VISION_INPUT"));
        when(toolService.createTool(any(), eq(7L))).thenReturn(saved);
        doThrow(new BusinessException(ErrorCode.PARAM_ERROR, "missing VISION_INPUT"))
                .when(modelCapabilityService).validateToolModelCapabilities(any(), eq(disabledModel));
        ConfigBundleDto bundle = bundle(toolBundle(
                "disabled_text",
                List.of("TEXT_GENERATION", "VISION_INPUT")
        ));

        ConfigBundleImportResult result = service.importBundle(bundle, 7L);

        verify(toolMapper, never()).updateToolModelConfig(10L, 44L, 7L);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("missing VISION_INPUT"));
    }

    private static ToolSummaryResponse summary(List<String> capabilities) {
        AiTool tool = new AiTool();
        tool.setId(10L);
        tool.setToolCode("capability_tool");
        tool.setToolName("Capability tool");
        tool.setCategoryId(3L);
        tool.setToolType("VIDEO_GENERATION");
        tool.setInputModality("TEXT");
        tool.setOutputModality("VIDEO");
        tool.setStatus("DRAFT");
        tool.setExecutionHandler("VIDEO_GENERATION");
        tool.setRequiredModelCapabilities(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(capabilities).toString());
        return ToolSummaryResponse.from(tool);
    }

    private static ConfigBundleDto bundle(ConfigBundleDto.Tool tool) {
        return new ConfigBundleDto(
                "ai-tool-market-config-bundle",
                2,
                null,
                null,
                "SELECTED_TOOLS",
                true,
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ConfigBundleDto.Category("media", "Media", 0, "ACTIVE")),
                List.of(tool)
        );
    }

    private static ConfigBundleDto.Tool toolBundle(String modelCode, List<String> capabilities) {
        return new ConfigBundleDto.Tool(
                "capability_tool",
                "Capability tool",
                "media",
                "test",
                "",
                "VIDEO_GENERATION",
                "TEXT",
                "VIDEO",
                null,
                "DRAFT",
                0,
                modelCode,
                "VIDEO_GENERATION",
                capabilities,
                false,
                List.of(),
                List.of(),
                null
        );
    }
}
