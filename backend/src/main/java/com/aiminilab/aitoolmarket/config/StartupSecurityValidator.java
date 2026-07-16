package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StartupSecurityValidator implements InitializingBean {

    private static final String DEFAULT_JWT_SECRET = "local-dev-secret";
    private static final String DEFAULT_INTERNAL_API_TOKEN = "local-internal-token";
    private static final int MIN_PRODUCTION_JWT_SECRET_LENGTH = 32;
    private static final int MIN_WORKFLOW_CONFIRMATION_SECRET_LENGTH = 32;

    private final AppProperties appProperties;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;
    private final String rabbitMqUsername;
    private final String rabbitMqPassword;

    public StartupSecurityValidator(AppProperties appProperties,
                                    WorkflowRuntimeProperties workflowRuntimeProperties,
                                    @Value("${spring.rabbitmq.username:guest}") String rabbitMqUsername,
                                    @Value("${spring.rabbitmq.password:guest}") String rabbitMqPassword) {
        this.appProperties = appProperties;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
        this.rabbitMqUsername = rabbitMqUsername;
        this.rabbitMqPassword = rabbitMqPassword;
    }

    @Override
    public void afterPropertiesSet() {
        if (!appProperties.isProductionEnvironment()) {
            return;
        }

        if (DEFAULT_JWT_SECRET.equals(appProperties.getJwtSecret())) {
            throw new IllegalStateException("Production mode requires a non-default JWT secret");
        }
        if (appProperties.getJwtSecret() == null
                || appProperties.getJwtSecret().length() < MIN_PRODUCTION_JWT_SECRET_LENGTH) {
            throw new IllegalStateException("Production mode requires a JWT secret with at least 32 characters");
        }
        if (DEFAULT_INTERNAL_API_TOKEN.equals(appProperties.getInternalApiToken())) {
            throw new IllegalStateException("Production mode requires a non-default internal API token");
        }
        if (appProperties.getCors().getAllowedOrigins().stream().anyMatch("*"::equals)) {
            throw new IllegalStateException("Production mode does not allow wildcard CORS origins");
        }
        if ("redis".equalsIgnoreCase(appProperties.getTaskQueueBackend())) {
            throw new IllegalStateException("Production mode requires RabbitMQ task queue backend");
        }
        validateRabbitMqCredentials();
        validateWorkflowRuntimeSafety();
        String confirmationSecret = appProperties.getWorkflow().getConfirmation().getHmacSecret();
        if (workflowRuntimeProperties.isConfirmationEnabled()
                && (confirmationSecret == null
                || confirmationSecret.length() < MIN_WORKFLOW_CONFIRMATION_SECRET_LENGTH)) {
            throw new IllegalStateException(
                    "Production mode requires a workflow confirmation HMAC secret with at least 32 characters"
            );
        }
    }

    private void validateRabbitMqCredentials() {
        if (rabbitMqUsername == null || rabbitMqUsername.isBlank()) {
            throw new IllegalStateException("Production mode requires a non-empty RabbitMQ username");
        }
        if ("guest".equalsIgnoreCase(rabbitMqUsername.trim())) {
            throw new IllegalStateException("Production mode does not allow the RabbitMQ guest username");
        }
        if (rabbitMqPassword == null || rabbitMqPassword.isBlank()) {
            throw new IllegalStateException("Production mode requires a non-empty RabbitMQ password");
        }
        if ("guest".equalsIgnoreCase(rabbitMqPassword.trim())) {
            throw new IllegalStateException("Production mode does not allow the RabbitMQ guest password");
        }
    }

    private void validateWorkflowRuntimeSafety() {
        if (!workflowRuntimeProperties.isEnabled() || !workflowRuntimeProperties.isExecutionEnabled()) {
            return;
        }
        if (workflowRuntimeProperties.getMaxProviderDailyCostCny() == null
                || workflowRuntimeProperties.getMaxProviderDailyCostCny().signum() <= 0) {
            throw new IllegalStateException(
                    "Production workflow execution requires a positive provider daily cost limit"
            );
        }
        if (workflowRuntimeProperties.getCostAlertWebhookUrl().isBlank()) {
            throw new IllegalStateException(
                    "Production workflow execution requires a cost alert webhook URL"
            );
        }
    }
}
