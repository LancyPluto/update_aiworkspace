package com.aiminilab.aitoolmarket.admin.proxy;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MihomoRuntimeServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private HttpClient httpClient;

    @Test
    void disabledRuntimeDoesNotReadSettingsWriteFilesOrCallController() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        MihomoRuntimeProperties properties = properties(false, configPath);
        MihomoRuntimeService service = service(properties);

        MihomoRuntimeResponse status = service.status();
        MihomoRuntimeResponse apply = service.apply();
        service.applyPersistedConfigOnApplicationReady();

        assertThat(status.managed()).isFalse();
        assertThat(apply.managed()).isFalse();
        assertThat(Files.exists(configPath)).isFalse();
        verify(systemSettingService, never()).settings();
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void applicationReadyRetriesUntilControllerAcceptsPersistedConfig() throws Exception {
        Path configPath = tempDir.resolve("mihomo/config.yaml");
        MihomoRuntimeProperties properties = properties(true, configPath);
        when(systemSettingService.settings()).thenReturn(Map.of(
                "outbound.proxy.sourceType", "MANUAL",
                "outbound.proxy.manualProtocol", "SOCKS5",
                "outbound.proxy.manualHost", "47.85.19.188",
                "outbound.proxy.manualPort", "1080"
        ));
        HttpResponse<String> unavailable = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        HttpResponse<String> accepted = mock(HttpResponse.class);
        when(accepted.statusCode()).thenReturn(204);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(unavailable, unavailable, accepted);

        Executor directExecutor = Runnable::run;
        MihomoRuntimeService service = new MihomoRuntimeService(
                systemSettingService,
                new MihomoConfigRenderer(),
                properties,
                httpClient,
                new ObjectMapper(),
                directExecutor,
                0
        );

        service.applyPersistedConfigOnApplicationReady();

        verify(httpClient, org.mockito.Mockito.times(3)).send(any(), any(HttpResponse.BodyHandler.class));
        assertThat(Files.readString(configPath)).contains(
                "type: 'socks5'",
                "server: '47.85.19.188'",
                "port: 1080"
        );
    }

    @Test
    void applicationReadyStopsAfterFiniteRetryBudget() throws Exception {
        MihomoRuntimeProperties properties = properties(true, tempDir.resolve("mihomo/config.yaml"));
        when(systemSettingService.settings()).thenReturn(Map.of(
                "outbound.proxy.sourceType", "MANUAL",
                "outbound.proxy.manualHost", "47.85.19.188",
                "outbound.proxy.manualPort", "1080"
        ));
        HttpResponse<String> unavailable = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(unavailable);
        MihomoRuntimeService service = new MihomoRuntimeService(
                systemSettingService,
                new MihomoConfigRenderer(),
                properties,
                httpClient,
                new ObjectMapper(),
                Runnable::run,
                0
        );

        service.applyPersistedConfigOnApplicationReady();

        verify(httpClient, org.mockito.Mockito.times(5)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void managedRuntimeAtomicallyWritesConfigBeforeReloadingController() throws Exception {
        Path configPath = tempDir.resolve("mihomo/config.yaml");
        MihomoRuntimeProperties properties = properties(true, configPath);
        when(systemSettingService.settings()).thenReturn(Map.of(
                "outbound.proxy.sourceType", "MANUAL",
                "outbound.proxy.manualProtocol", "SOCKS5",
                "outbound.proxy.manualHost", "8.8.8.8",
                "outbound.proxy.manualPort", "1080"
        ));
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            assertThat(Files.readString(configPath)).contains("type: 'socks5'");
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.uri().toString()).isEqualTo("http://mihomo:9090/configs?force=true");
            assertThat(request.headers().firstValue("Authorization"))
                    .contains("Bearer controller-secret");
            return response;
        });

        MihomoRuntimeResponse result = service(properties).apply();

        assertThat(result.managed()).isTrue();
        assertThat(result.available()).isTrue();
        assertThat(result.lastAppliedAt()).isNotNull();
        assertThat(Files.readString(configPath)).contains("external-controller: '0.0.0.0:9090'");
        verify(httpClient).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void statusReturnsControllerVersionWhenManaged() throws Exception {
        MihomoRuntimeProperties properties = properties(true, tempDir.resolve("config.yaml"));
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"version\":\"v1.19.10\",\"meta\":true}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        MihomoRuntimeResponse result = service(properties).status();

        assertThat(result.managed()).isTrue();
        assertThat(result.available()).isTrue();
        assertThat(result.version()).isEqualTo("v1.19.10");
    }

    private MihomoRuntimeService service(MihomoRuntimeProperties properties) {
        return new MihomoRuntimeService(
                systemSettingService,
                new MihomoConfigRenderer(),
                properties,
                httpClient,
                new ObjectMapper()
        );
    }

    private MihomoRuntimeProperties properties(boolean enabled, Path configPath) {
        MihomoRuntimeProperties properties = new MihomoRuntimeProperties();
        properties.setEnabled(enabled);
        properties.setControllerUrl("http://mihomo:9090");
        properties.setControllerSecret("controller-secret");
        properties.setConfigPath(configPath);
        properties.setReloadPath("/root/.config/mihomo/config.yaml");
        return properties;
    }
}
