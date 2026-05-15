package com.aiminilab.aitoolmarket.commerce;

import com.aiminilab.aitoolmarket.commerce.payment.PaymentProviderRegistry;
import com.aiminilab.aitoolmarket.commerce.service.CommercePlatformService;
import com.aiminilab.aitoolmarket.commerce.service.ModelGatewayService;
import com.aiminilab.aitoolmarket.commerce.service.ModelNodeConcurrencyService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class CommercePlatformServiceTest {

    @Test
    void batchHealthCheckDisablesFailedNodesAndReturnsSummary() {
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        ModelGatewayService modelGatewayService = Mockito.mock(ModelGatewayService.class);
        CommercePlatformService service = new CommercePlatformService(
                jdbcTemplate,
                Mockito.mock(CreditService.class),
                Mockito.mock(PaymentProviderRegistry.class),
                modelGatewayService,
                Mockito.mock(ModelNodeConcurrencyService.class)
        );
        Mockito.when(jdbcTemplate.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(1L, 2L));
        Mockito.doThrow(new RuntimeException("provider down")).when(modelGatewayService).healthCheck(2L);
        Mockito.when(jdbcTemplate.queryForMap(anyString(), eq(1L))).thenReturn(nodeRow(1L, "AVAILABLE", "HEALTHY"));
        Mockito.when(jdbcTemplate.queryForMap(anyString(), eq(2L))).thenReturn(nodeRow(2L, "DISABLED", "UNHEALTHY"));

        Map<String, Object> summary = service.adminHealthCheckNodes(null);

        assertThat(summary)
                .containsEntry("checked", 2)
                .containsEntry("healthy", 1)
                .containsEntry("unhealthy", 1)
                .containsEntry("auto_offline", 1);
        Mockito.verify(jdbcTemplate).update(Mockito.contains("SET status = 'DISABLED'"),
                eq("provider down"), eq(2L));
    }

    private static Map<String, Object> nodeRow(Long id, String status, String healthStatus) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("pool_id", 10L),
                Map.entry("node_code", "node-" + id),
                Map.entry("model_name", "demo-model"),
                Map.entry("max_concurrency", 2),
                Map.entry("current_concurrency", 0),
                Map.entry("today_used", 0),
                Map.entry("status", status),
                Map.entry("health_status", healthStatus)
        );
    }
}
