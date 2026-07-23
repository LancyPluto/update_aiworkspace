package com.aiminilab.aitoolmarket.common.error;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.error-contract")
public class ErrorContractProperties {

    private ErrorContractMode mode = ErrorContractMode.LEGACY;

    public ErrorContractMode getMode() {
        return mode;
    }

    public void setMode(ErrorContractMode mode) {
        this.mode = mode == null ? ErrorContractMode.LEGACY : mode;
    }

    public boolean isV2() {
        return mode == ErrorContractMode.V2;
    }
}
