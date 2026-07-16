package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolDescriptorExtensionMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolDescriptorExtension;
import com.aiminilab.aitoolmarket.agent.service.impl.AgentToolDescriptorServiceImpl;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.dto.ToolFieldResponse;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolDescriptorServiceImplTest {

    @Mock
    ToolMapper toolMapper;
    @Mock
    ToolFieldItemMapper toolFieldItemMapper;
    @Mock
    AgentToolDescriptorExtensionMapper extensionMapper;
    @Mock
    AgentToolPreferenceMapper preferenceMapper;
    @Mock
    AgentModelConfigMapper modelConfigMapper;
    @Mock
    TaskCreditEstimateService taskCreditEstimateService;

    @Test
    void workflowDescriptorExposesInternalExecutionContract() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentToolDescriptorServiceImpl service = new AgentToolDescriptorServiceImpl(
                toolMapper,
                toolFieldItemMapper,
                extensionMapper,
                preferenceMapper,
                modelConfigMapper,
                taskCreditEstimateService,
                objectMapper
        );
        AiTool tool = new AiTool();
        tool.setId(19L);
        tool.setToolCode("ai_comic_drama_agent");
        tool.setToolName("AI Comic Drama");
        tool.setExecutionMode("WORKFLOW");
        tool.setBillingMode("WORKFLOW_STEP");
        tool.setMinimumRequiredCredits(12);
        AgentToolDescriptorExtension extension = new AgentToolDescriptorExtension();
        extension.setRiskLevel("MEDIUM");
        extension.setConfirmationPolicy("WORKFLOW_DEFINED");

        when(toolMapper.findOnlineByCode(tool.getToolCode())).thenReturn(Optional.of(tool));
        when(extensionMapper.findByToolCode(tool.getToolCode())).thenReturn(Optional.of(extension));
        when(preferenceMapper.findByUserIdAndToolCode(7L, tool.getToolCode())).thenReturn(null);
        when(toolFieldItemMapper.findActiveFields(tool.getId())).thenReturn(List.of());
        when(taskCreditEstimateService.estimateUserFacingTaskCredits(tool)).thenReturn(12);

        var descriptor = service.getToolForAgent(7L, tool.getToolCode());

        assertThat(descriptor.executionMode()).isEqualTo("WORKFLOW");
        assertThat(descriptor.billingMode()).isEqualTo("WORKFLOW_STEP");
        assertThat(descriptor.minimumRequiredCredits()).isEqualTo(12);
        assertThat(descriptor.runRouteTemplate()).isEqualTo("/agents/runs/{taskId}");
        assertThat(descriptor.riskLevel()).isEqualTo("MEDIUM");
        assertThat(descriptor.confirmationPolicy()).isEqualTo("WORKFLOW_DEFINED");
    }

    @Test
    void directDescriptorUsesSafeExecutionDefaultsWithoutWorkflowRoute() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentToolDescriptorServiceImpl service = new AgentToolDescriptorServiceImpl(
                toolMapper,
                toolFieldItemMapper,
                extensionMapper,
                preferenceMapper,
                modelConfigMapper,
                taskCreditEstimateService,
                objectMapper
        );
        AiTool tool = new AiTool();
        tool.setId(20L);
        tool.setToolCode("direct_image_tool");
        tool.setToolName("Direct Image Tool");

        when(toolMapper.findOnlineByCode(tool.getToolCode())).thenReturn(Optional.of(tool));
        when(extensionMapper.findByToolCode(tool.getToolCode())).thenReturn(Optional.empty());
        when(preferenceMapper.findByUserIdAndToolCode(7L, tool.getToolCode())).thenReturn(null);
        when(toolFieldItemMapper.findActiveFields(tool.getId())).thenReturn(List.of());

        var descriptor = service.getToolForAgent(7L, tool.getToolCode());

        assertThat(descriptor.executionMode()).isEqualTo("DIRECT");
        assertThat(descriptor.billingMode()).isEqualTo("FIXED");
        assertThat(descriptor.minimumRequiredCredits()).isZero();
        assertThat(descriptor.runRouteTemplate()).isNull();
        assertThat(descriptor.riskLevel()).isEqualTo("low");
        assertThat(descriptor.confirmationPolicy()).isEqualTo("auto");
    }

    @Test
    void customModeOptionsObjectCompilesToBooleanEnumForAgentSchema() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentToolDescriptorServiceImpl service = new AgentToolDescriptorServiceImpl(
                toolMapper,
                toolFieldItemMapper,
                extensionMapper,
                preferenceMapper,
                modelConfigMapper,
                taskCreditEstimateService,
                objectMapper
        );
        ToolFieldResponse customMode = new ToolFieldResponse(
                "customMode",
                "创作模式",
                "radio",
                "常规：仅描述想法；高级：自定义歌词、风格与标题",
                objectMapper.readTree("{\"options\":[{\"label\":\"常规\",\"value\":\"false\"},{\"label\":\"高级\",\"value\":\"true\"}]}"),
                "{\"options\":[{\"label\":\"常规\",\"value\":\"false\"},{\"label\":\"高级\",\"value\":\"true\"}]}",
                true,
                true,
                false,
                "false",
                "default",
                "LOW",
                3
        );

        Method method = AgentToolDescriptorServiceImpl.class.getDeclaredMethod("toInputSchema", String.class, List.class);
        method.setAccessible(true);
        JsonNode schema = (JsonNode) method.invoke(service, "suno_music", List.of(customMode));
        JsonNode property = schema.path("properties").path("customMode");

        assertThat(property.path("type").asText()).isEqualTo("boolean");
        assertThat(property.path("enum").get(0).asBoolean()).isFalse();
        assertThat(property.path("enum").get(1).asBoolean()).isTrue();
        assertThat(property.path("default").asBoolean()).isFalse();
        assertThat(schema.path("required").get(0).asText()).isEqualTo("customMode");
    }
}
