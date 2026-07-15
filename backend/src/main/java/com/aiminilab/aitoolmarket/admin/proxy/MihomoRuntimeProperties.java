package com.aiminilab.aitoolmarket.admin.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.mihomo")
public class MihomoRuntimeProperties {
    private boolean enabled;
    private String controllerUrl = "http://mihomo:9090";
    private String controllerSecret = "";
    private Path configPath = Path.of("/data/mihomo/config.yaml");
    private String reloadPath = "/root/.config/mihomo/config.yaml";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getControllerUrl() {
        return controllerUrl;
    }

    public void setControllerUrl(String controllerUrl) {
        this.controllerUrl = controllerUrl;
    }

    public String getControllerSecret() {
        return controllerSecret;
    }

    public void setControllerSecret(String controllerSecret) {
        this.controllerSecret = controllerSecret;
    }

    public Path getConfigPath() {
        return configPath;
    }

    public void setConfigPath(Path configPath) {
        this.configPath = configPath;
    }

    public String getReloadPath() {
        return reloadPath;
    }

    public void setReloadPath(String reloadPath) {
        this.reloadPath = reloadPath;
    }
}
