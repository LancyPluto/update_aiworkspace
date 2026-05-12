package com.aiminilab.aitoolmarket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean productionMode;
    private String jwtSecret;
    private String internalApiToken;
    private String aiTaskQueue;
    private Agent agent = new Agent();
    private Cors cors = new Cors();

    public boolean isProductionMode() {
        return productionMode;
    }

    public void setProductionMode(boolean productionMode) {
        this.productionMode = productionMode;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getInternalApiToken() {
        return internalApiToken;
    }

    public void setInternalApiToken(String internalApiToken) {
        this.internalApiToken = internalApiToken;
    }

    public String getAiTaskQueue() {
        return aiTaskQueue;
    }

    public void setAiTaskQueue(String aiTaskQueue) {
        this.aiTaskQueue = aiTaskQueue;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent == null ? new Agent() : agent;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
        }
    }

    public static class Agent {
        private boolean enabled = true;
        private String serviceBaseUrl = "http://127.0.0.1:8090";
        private int maxActiveRunsPerUser = 1;
        private int maxMessagesPerMinute = 10;
        private int maxRunsPerHour = 30;
        private int defaultCreditBudget = 20;
        private String fileStorageDir = "data/agent-files";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServiceBaseUrl() {
            return serviceBaseUrl;
        }

        public void setServiceBaseUrl(String serviceBaseUrl) {
            this.serviceBaseUrl = serviceBaseUrl;
        }

        public int getMaxActiveRunsPerUser() {
            return maxActiveRunsPerUser;
        }

        public void setMaxActiveRunsPerUser(int maxActiveRunsPerUser) {
            this.maxActiveRunsPerUser = maxActiveRunsPerUser;
        }

        public int getMaxMessagesPerMinute() {
            return maxMessagesPerMinute;
        }

        public void setMaxMessagesPerMinute(int maxMessagesPerMinute) {
            this.maxMessagesPerMinute = maxMessagesPerMinute;
        }

        public int getMaxRunsPerHour() {
            return maxRunsPerHour;
        }

        public void setMaxRunsPerHour(int maxRunsPerHour) {
            this.maxRunsPerHour = maxRunsPerHour;
        }

        public int getDefaultCreditBudget() {
            return defaultCreditBudget;
        }

        public void setDefaultCreditBudget(int defaultCreditBudget) {
            this.defaultCreditBudget = defaultCreditBudget;
        }

        public String getFileStorageDir() {
            return fileStorageDir;
        }

        public void setFileStorageDir(String fileStorageDir) {
            this.fileStorageDir = fileStorageDir == null || fileStorageDir.isBlank() ? "data/agent-files" : fileStorageDir;
        }
    }
}
