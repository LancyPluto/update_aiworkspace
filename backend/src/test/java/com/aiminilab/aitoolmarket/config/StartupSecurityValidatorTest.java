package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.AiToolMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StartupSecurityValidatorTest {

    private ApplicationContextRunner contextRunner(String databaseName) {
        return new ApplicationContextRunner()
            .withUserConfiguration(AiToolMarketApplication.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.sql.init.mode=always",
                    "spring.sql.init.schema-locations=classpath:schema-test.sql",
                    "spring.rabbitmq.username=workflow-app",
                    "spring.rabbitmq.password=strong-rabbitmq-password"
            );
    }

    @Test
    void productionModeRejectsDefaultJwtSecret() {
        contextRunner("startup_security_default_jwt_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=local-dev-secret",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionModeRejectsDefaultInternalToken() {
        contextRunner("startup_security_default_internal_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=local-internal-token",
                        "app.cors.allowed-origins=http://localhost:5173"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionModeRejectsWildcardCorsOrigin() {
        contextRunner("startup_security_wildcard_cors_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=*"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void appEnvProductionRejectsDefaultInternalToken() {
        contextRunner("startup_security_app_env_production_test")
                .withPropertyValues(
                        "app.env=production",
                        "app.production-mode=false",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=local-internal-token",
                        "app.cors.allowed-origins=http://localhost:5173"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionModeRejectsRedisTaskQueueBackend() {
        contextRunner("startup_security_redis_queue_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=redis"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionModeRejectsMissingWorkflowConfirmationSecret() {
        contextRunner("startup_security_workflow_confirmation_secret_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "workflow.runtime.confirmation-enabled=true",
                        "app.workflow.confirmation.hmac-secret="
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionModeAllowsMissingWorkflowSecretWhenConfirmationIsDisabled() {
        contextRunner("startup_security_workflow_confirmation_disabled_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "workflow.runtime.confirmation-enabled=false",
                        "app.workflow.confirmation.hmac-secret="
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionModeRejectsBlankRabbitMqUsername() {
        contextRunner("startup_security_blank_rabbitmq_username_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "spring.rabbitmq.username="
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production mode requires a non-empty RabbitMQ username");
                });
    }

    @Test
    void productionModeRejectsGuestRabbitMqUsername() {
        contextRunner("startup_security_guest_rabbitmq_username_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "spring.rabbitmq.username=guest"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production mode does not allow the RabbitMQ guest username");
                });
    }

    @Test
    void productionModeRejectsBlankRabbitMqPassword() {
        contextRunner("startup_security_blank_rabbitmq_password_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "spring.rabbitmq.password="
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production mode requires a non-empty RabbitMQ password");
                });
    }

    @Test
    void productionModeRejectsGuestRabbitMqPassword() {
        contextRunner("startup_security_guest_rabbitmq_password_test")
                .withPropertyValues(
                        "app.production-mode=true",
                        "app.jwt-secret=strong-jwt-secret-value-1234567890",
                        "app.internal-api-token=strong-internal-token-value-123456",
                        "app.cors.allowed-origins=http://localhost:5173",
                        "app.task-queue-backend=rabbitmq",
                        "spring.rabbitmq.password=guest"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production mode does not allow the RabbitMQ guest password");
                });
    }

    @Test
    void developmentModeAllowsLocalDefaults() {
        contextRunner("startup_security_development_defaults_test")
                .withPropertyValues(
                        "app.production-mode=false",
                        "app.jwt-secret=local-dev-secret",
                        "app.internal-api-token=local-internal-token",
                        "app.cors.allowed-origins=http://localhost:5173,http://localhost:5174",
                        "spring.rabbitmq.username=guest",
                        "spring.rabbitmq.password=guest"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
