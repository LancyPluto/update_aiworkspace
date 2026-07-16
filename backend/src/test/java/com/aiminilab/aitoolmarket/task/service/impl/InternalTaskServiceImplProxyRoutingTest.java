package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.agent.support.OutboundProxyPolicyResolver;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(toolMapper.findById(9L)).thenReturn(Optional.of(new AiTool()));
        when(fieldMapper.findActiveFields(9L)).thenReturn(List.of());

        ModelExecutionSnapshot snapshot = objectMapper.readValue("""
                {
                  "id": 3,
                  "baseUrl": "https://api.ofox.ai/v1",
                  "extraAuthJson": "{\\\"proxyMode\\\":\\\"disabled\\\"}",
                  "capabilities": [],
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
}
