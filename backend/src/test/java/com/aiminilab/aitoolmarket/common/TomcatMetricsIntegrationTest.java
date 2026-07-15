package com.aiminilab.aitoolmarket.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:tomcat_metrics_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.hikari.connection-init-sql=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:schema-test.sql",
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.prometheus.metrics.export.enabled=true",
                "management.metrics.distribution.percentiles-histogram.http.server.requests=true",
                "management.health.redis.enabled=false",
                "management.health.rabbit.enabled=false",
                "server.tomcat.mbeanregistry.enabled=true",
                "app.cache.enabled=false",
                "app.task-queue-backend=redis"
        })
class TomcatMetricsIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean(name = "rabbitTopologyInitializer")
    private ApplicationRunner rabbitTopologyInitializer;

    @Test
    void prometheusExposesHttpHistogramAndTomcatThreadMetrics() throws Exception {
        ResponseEntity<String> pingResponse = restTemplate.getForEntity("/api/v1/ping", String.class);
        assertThat(pingResponse.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> actuatorResponse = restTemplate.getForEntity("/actuator", String.class);
        ResponseEntity<String> metricsResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(metricsResponse.getStatusCode().is2xxSuccessful())
                .as("Actuator links: %s", actuatorResponse.getBody())
                .isTrue();
        assertThat(metricsResponse.getBody())
                .contains("http_server_requests_seconds_bucket")
                .contains("tomcat_threads_busy_threads")
                .contains("tomcat_threads_config_max_threads");
        verify(rabbitTopologyInitializer).run(any());
    }
}
