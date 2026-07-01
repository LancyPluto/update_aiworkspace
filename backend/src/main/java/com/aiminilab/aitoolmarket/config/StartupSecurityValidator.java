package com.aiminilab.aitoolmarket.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class StartupSecurityValidator implements InitializingBean {

    private static final String DEFAULT_JWT_SECRET = "local-dev-secret";
    private static final String DEFAULT_INTERNAL_API_TOKEN = "local-internal-token";
    private static final int MIN_PRODUCTION_JWT_SECRET_LENGTH = 32;

    private final AppProperties appProperties;

    public StartupSecurityValidator(AppProperties appProperties) {
        this.appProperties = appProperties;
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
    }
}
