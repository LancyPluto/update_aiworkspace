package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MihomoNodeServiceTest {

    @Test
    void listsSubscriptionNodesAndResolvesAutomaticActiveNode() throws Exception {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.settings()).thenReturn(Map.of("outbound.proxy.sourceType", "SUBSCRIPTION"));
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String path = request.uri().getPath();
            if (path.endsWith("/proxies/PROXY")) {
                return response(200, "{\"now\":\"AUTO-NODE\",\"all\":[\"AUTO-NODE\",\"Tokyo\",\"Seattle\"]}");
            }
            if (path.endsWith("/proxies/AUTO-NODE")) {
                return response(200, "{\"now\":\"Tokyo\"}");
            }
            return response(200, """
                    {"proxies":[
                      {"name":"Tokyo","type":"ss","alive":true,"history":[{"delay":82}]},
                      {"name":"Seattle","type":"vmess","alive":false,"history":[{"delay":0}]}
                    ]}
                    """);
        });
        MihomoNodeService service = new MihomoNodeService(settings, enabledProperties(), new ObjectMapper(), client);

        MihomoNodeListResponse result = service.list();

        assertThat(result.available()).isTrue();
        assertThat(result.selectionMode()).isEqualTo("AUTO");
        assertThat(result.activeNode()).isEqualTo("Tokyo");
        assertThat(result.nodes()).extracting(MihomoNodeItem::name).containsExactly("Tokyo", "Seattle");
        assertThat(result.nodes().get(0).selected()).isTrue();
        assertThat(result.nodes().get(0).latencyMs()).isEqualTo(82);
    }

    @Test
    void manualSelectionValidatesSubscriptionNodeBeforeCallingProxyGroup() throws Exception {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.settings()).thenReturn(Map.of("outbound.proxy.sourceType", "SUBSCRIPTION"));
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            if (request.method().equals("GET")) {
                return response(200, "{\"proxies\":[{\"name\":\"Tokyo\",\"alive\":true}]}");
            }
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.uri().getPath()).endsWith("/proxies/PROXY");
            return response(204, "");
        });
        MihomoNodeService service = new MihomoNodeService(settings, enabledProperties(), new ObjectMapper(), client);

        MihomoNodeActionResponse result = service.select(new MihomoNodeSelectionRequest("MANUAL", "Tokyo"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void disabledRuntimeReturnsEmptyNodeStateWithoutCallingController() throws Exception {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.settings()).thenReturn(Map.of("outbound.proxy.sourceType", "SUBSCRIPTION"));
        HttpClient client = mock(HttpClient.class);
        MihomoRuntimeProperties properties = enabledProperties();
        properties.setEnabled(false);
        MihomoNodeService service = new MihomoNodeService(settings, properties, new ObjectMapper(), client);

        MihomoNodeListResponse result = service.list();

        assertThat(result.managed()).isFalse();
        assertThat(result.nodes()).isEmpty();
        verify(client, never()).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private MihomoRuntimeProperties enabledProperties() {
        MihomoRuntimeProperties properties = new MihomoRuntimeProperties();
        properties.setEnabled(true);
        properties.setControllerUrl("http://mihomo:9090");
        properties.setControllerSecret("secret");
        return properties;
    }
}
