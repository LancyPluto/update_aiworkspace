package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.agent.support.OutboundProxyPolicyResolver;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.storage.PrivateAssetAccessService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunLockService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalTaskServiceImplProxyRoutingTest {

    @Test
    void executionContextKeepsSnapshotForAuditButUsesCurrentRoutingPolicy() throws Exception {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        OutboundProxyPolicyResolver resolver = mock(OutboundProxyPolicyResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setParamsJson("{}");
        task.setModelSnapshotJson("historical-snapshot");
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));
        AiTool tool = new AiTool();
        tool.setRequiredModelCapabilities("[\"TEXT_GENERATION\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(tool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "baseUrl": "https://api.ofox.ai/v1",
                  "extraAuthJson": "{\\\"proxyMode\\\":\\\"disabled\\\"}",
                  "capabilities": ["TEXT_GENERATION"],
                  "proxyPolicy": {
                    "mode": "INHERIT",
                    "proxyUrl": "socks5://historical.example:1080",
                    "enabled": true,
                    "noProxyHosts": []
                  }
                }
                """, ModelExecutionSnapshot.class);
        ProxyPolicy currentPolicy = new ProxyPolicy(
                "PROXY", "http://mihomo:7890", true, List.of(), List.of(),
                "DIRECT", "http://mihomo:7890", true
        );
        when(snapshotService.parse("historical-snapshot")).thenReturn(snapshot);
        when(resolver.resolve(any())).thenReturn(currentPolicy);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, mock(AgentModelConfigMapper.class),
                mock(AgentToolDescriptorService.class), mock(AgentModelConfigService.class),
                mock(ModelCapabilityService.class), snapshotService, resolver, fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        var response = service.executionContext(42L);

        assertThat(response.modelSnapshot()).isSameAs(snapshot);
        assertThat(response.modelSnapshot().proxyPolicy().proxyUrl()).startsWith("socks5://");
        assertThat(response.modelConfig().proxyPolicy()).isSameAs(currentPolicy);
        assertThat(response.modelConfig().proxyPolicy().proxyUrl()).isEqualTo("http://mihomo:7890");
    }

    @Test
    void executionContextRejectsSnapshotMissingAnyRequiredCapabilityWithoutReadingLiveModel() throws Exception {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        OutboundProxyPolicyResolver resolver = mock(OutboundProxyPolicyResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setParamsJson("{}");
        task.setModelSnapshotJson("historical-snapshot");
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));

        AiTool tool = new AiTool();
        tool.setRequiredModelCapabilities("[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(tool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "provider": "snapshot-only",
                  "capabilities": [" text_generation "]
                }
                """, ModelExecutionSnapshot.class);
        when(snapshotService.parse("historical-snapshot")).thenReturn(snapshot);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, modelConfigMapper,
                mock(AgentToolDescriptorService.class), modelConfigService,
                capabilityService, snapshotService, resolver, fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        assertThatThrownBy(() -> service.executionContext(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");

        verifyNoInteractions(modelConfigMapper, modelConfigService, capabilityService, resolver);
    }

    @Test
    void executionContextDoesNotApplyRootToolCapabilitiesToWorkflowStepSnapshot() throws Exception {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        OutboundProxyPolicyResolver resolver = mock(OutboundProxyPolicyResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setParamsJson("""
                {
                  "workflowStep": true,
                  "workflowStepId": 77,
                  "nodeDefType": "LLM_TEXT",
                  "nodeParameters": {"requiredCapability": "TEXT_GENERATION"}
                }
                """);
        task.setModelSnapshotJson("workflow-step-snapshot");
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));

        AiTool rootTool = new AiTool();
        rootTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(rootTool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "provider": "text-provider",
                  "capabilities": ["TEXT_GENERATION"]
                }
                """, ModelExecutionSnapshot.class);
        when(snapshotService.parse("workflow-step-snapshot")).thenReturn(snapshot);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, mock(AgentModelConfigMapper.class),
                mock(AgentToolDescriptorService.class), mock(AgentModelConfigService.class),
                mock(ModelCapabilityService.class), snapshotService, resolver, fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        var response = service.executionContext(42L);

        assertThat(response.modelSnapshot()).isSameAs(snapshot);
        assertThat(response.modelSnapshot().capabilities()).containsExactly("TEXT_GENERATION");
    }

    @Test
    void executionContextRejectsWorkflowStepSnapshotMissingNodeCapability() throws Exception {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setParamsJson("""
                {
                  "workflowStep": true,
                  "workflowStepId": 77,
                  "nodeDefType": "LLM_TEXT",
                  "nodeParameters": {"requiredCapability": "TEXT_GENERATION"}
                }
                """);
        task.setModelSnapshotJson("workflow-step-video-snapshot");
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));

        AiTool rootTool = new AiTool();
        rootTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(rootTool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "provider": "video-provider",
                  "capabilities": ["VIDEO_GENERATION"]
                }
                """, ModelExecutionSnapshot.class);
        when(snapshotService.parse("workflow-step-video-snapshot")).thenReturn(snapshot);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, mock(AgentModelConfigMapper.class),
                mock(AgentToolDescriptorService.class), mock(AgentModelConfigService.class),
                mock(ModelCapabilityService.class), snapshotService, mock(OutboundProxyPolicyResolver.class),
                fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        assertThatThrownBy(() -> service.executionContext(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TEXT_GENERATION");
    }

    @Test
    void executionContextAcceptsLegacyDigitalHumanSnapshotAsVideoCapability() throws Exception {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        OutboundProxyPolicyResolver resolver = mock(OutboundProxyPolicyResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setExecutionHandler("DIGITAL_HUMAN");
        task.setParamsJson("{}");
        task.setModelSnapshotJson("legacy-digital-human-snapshot");
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));

        AiTool tool = new AiTool();
        tool.setExecutionHandler("DIGITAL_HUMAN");
        tool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(tool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "provider": "siliconflow",
                  "modelName": "legacy-digital-human",
                  "capabilities": ["DIGITAL_HUMAN"]
                }
                """, ModelExecutionSnapshot.class);
        when(snapshotService.parse("legacy-digital-human-snapshot")).thenReturn(snapshot);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, mock(AgentModelConfigMapper.class),
                mock(AgentToolDescriptorService.class), mock(AgentModelConfigService.class),
                mock(ModelCapabilityService.class), snapshotService, resolver, fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        var response = service.executionContext(42L);

        assertThat(response.modelSnapshot()).isSameAs(snapshot);
        assertThat(response.executionHandler()).isEqualTo("DIGITAL_HUMAN");
    }

    @Test
    void executionContextWithoutSnapshotUsesWorkflowNodeModelInsteadOfRootToolContract() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        ToolFieldItemMapper fieldMapper = mock(ToolFieldItemMapper.class);
        AgentModelConfigMapper modelConfigMapper = mock(AgentModelConfigMapper.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
        ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
        OutboundProxyPolicyResolver resolver = mock(OutboundProxyPolicyResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        AiTask task = new AiTask();
        task.setId(42L);
        task.setUserId(7L);
        task.setToolId(9L);
        task.setParamsJson("""
                {
                  "workflowStep": true,
                  "workflowStepId": 77,
                  "nodeDefType": "LLM_TEXT",
                  "nodeParameters": {"modelConfigId": 3}
                }
                """);
        when(taskMapper.findById(42L)).thenReturn(Optional.of(task));

        AiTool rootTool = new AiTool();
        rootTool.setRequiredModelCapabilities("[\"VIDEO_GENERATION\"]");
        when(toolMapper.findById(9L)).thenReturn(Optional.of(rootTool));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        AgentModelConfig nodeModel = new AgentModelConfig();
        nodeModel.setId(3L);
        nodeModel.setProvider("text-provider");
        nodeModel.setModelName("text-model");
        nodeModel.setEnabled(true);
        when(modelConfigMapper.findActiveById(3L)).thenReturn(nodeModel);
        when(capabilityService.resolveCapabilities(nodeModel)).thenReturn(List.of("TEXT_GENERATION"));
        when(modelConfigService.resolveForExecution(nodeModel)).thenReturn(nodeModel);

        InternalTaskServiceImpl service = new InternalTaskServiceImpl(
                taskMapper, toolMapper, modelConfigMapper,
                mock(AgentToolDescriptorService.class), modelConfigService,
                capabilityService, snapshotService, resolver, fieldMapper, objectMapper,
                mock(CreditService.class), mock(PricingService.class), mock(BillingService.class),
                mock(TaskMetrics.class), mock(CommunityService.class), mock(WorkflowStepCallbackService.class),
                mock(WorkflowRunLockService.class),
                mock(PrivateAssetAccessService.class), new AppProperties()
        );

        var response = service.executionContext(42L);

        assertThat(response.modelConfig().id()).isEqualTo(3L);
        assertThat(response.modelConfig().capabilities()).containsExactly("TEXT_GENERATION");
        verify(capabilityService).validateModelCapabilities(nodeModel, List.of("TEXT_GENERATION"));
        verify(capabilityService).validateModelExecution(nodeModel, List.of("TEXT_GENERATION"));
        verify(capabilityService, never()).validateExecution(any(), any());
        verify(capabilityService, never()).resolveModelConfigForTool(any());
    }
}
