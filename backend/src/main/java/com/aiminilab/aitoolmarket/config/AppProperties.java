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
    private String generatedMediaDir = "../data/generated-media";
    private Agent agent = new Agent();
    private Auth auth = new Auth();
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

    public String getGeneratedMediaDir() {
        return generatedMediaDir;
    }

    public void setGeneratedMediaDir(String generatedMediaDir) {
        this.generatedMediaDir = generatedMediaDir == null || generatedMediaDir.isBlank()
                ? "../data/generated-media"
                : generatedMediaDir;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent == null ? new Agent() : agent;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth == null ? new Auth() : auth;
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

    public static class Auth {
        private String cookieSameSite = "Lax";
        private Boolean cookieSecure;
        private Sms sms = new Sms();

        public String getCookieSameSite() {
            return cookieSameSite;
        }

        public void setCookieSameSite(String cookieSameSite) {
            this.cookieSameSite = cookieSameSite == null || cookieSameSite.isBlank() ? "Lax" : cookieSameSite;
        }

        public Boolean getCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(Boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public Sms getSms() {
            return sms;
        }

        public void setSms(Sms sms) {
            this.sms = sms == null ? new Sms() : sms;
        }
    }

    public static class Sms {
        private String provider = "local";
        private String bmobApplicationId = "";
        private String bmobRestApiKey;
        private String bmobBaseUrl = "https://api.bmob.cn";
        private String bmobTemplate;
        private String ihuyiApiId = "";
        private String ihuyiApiKey;
        private String ihuyiBaseUrl = "https://api.ihuyi.com/sms/Submit.json";
        private String ihuyiTemplateId = "1";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null || provider.isBlank() ? "local" : provider;
        }

        public String getBmobApplicationId() {
            return bmobApplicationId;
        }

        public void setBmobApplicationId(String bmobApplicationId) {
            this.bmobApplicationId = bmobApplicationId;
        }

        public String getBmobRestApiKey() {
            return bmobRestApiKey;
        }

        public void setBmobRestApiKey(String bmobRestApiKey) {
            this.bmobRestApiKey = bmobRestApiKey;
        }

        public String getBmobBaseUrl() {
            return bmobBaseUrl;
        }

        public void setBmobBaseUrl(String bmobBaseUrl) {
            this.bmobBaseUrl = bmobBaseUrl == null || bmobBaseUrl.isBlank() ? "https://api.bmob.cn" : bmobBaseUrl;
        }

        public String getBmobTemplate() {
            return bmobTemplate;
        }

        public void setBmobTemplate(String bmobTemplate) {
            this.bmobTemplate = bmobTemplate;
        }

        public String getIhuyiApiId() {
            return ihuyiApiId;
        }

        public void setIhuyiApiId(String ihuyiApiId) {
            this.ihuyiApiId = ihuyiApiId;
        }

        public String getIhuyiApiKey() {
            return ihuyiApiKey;
        }

        public void setIhuyiApiKey(String ihuyiApiKey) {
            this.ihuyiApiKey = ihuyiApiKey;
        }

        public String getIhuyiBaseUrl() {
            return ihuyiBaseUrl;
        }

        public void setIhuyiBaseUrl(String ihuyiBaseUrl) {
            this.ihuyiBaseUrl = ihuyiBaseUrl == null || ihuyiBaseUrl.isBlank()
                    ? "https://api.ihuyi.com/sms/Submit.json"
                    : ihuyiBaseUrl;
        }

        public String getIhuyiTemplateId() {
            return ihuyiTemplateId;
        }

        public void setIhuyiTemplateId(String ihuyiTemplateId) {
            this.ihuyiTemplateId = ihuyiTemplateId == null || ihuyiTemplateId.isBlank() ? "1" : ihuyiTemplateId;
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
