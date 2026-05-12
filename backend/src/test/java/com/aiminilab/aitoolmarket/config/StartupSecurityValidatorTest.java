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
                    "spring.sql.init.schema-locations=classpath:schema-test.sql"
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
    void developmentModeAllowsLocalDefaults() {
        contextRunner("startup_security_development_defaults_test")
                .withPropertyValues(
                        "app.production-mode=false",
                        "app.jwt-secret=local-dev-secret",
                        "app.internal-api-token=local-internal-token",
                        "app.cors.allowed-origins=http://localhost:5173,http://localhost:5174"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
