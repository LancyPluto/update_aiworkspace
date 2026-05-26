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
    private String taskQueueBackend = "rabbitmq";
    private Rabbitmq rabbitmq = new Rabbitmq();
    private String generatedMediaDir = "../data/generated-media";
    private Agent agent = new Agent();
    private Auth auth = new Auth();
    private Cors cors = new Cors();
    private Payment payment = new Payment();

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

    public String getTaskQueueBackend() {
        return taskQueueBackend;
    }

    public void setTaskQueueBackend(String taskQueueBackend) {
        this.taskQueueBackend = taskQueueBackend == null || taskQueueBackend.isBlank() ? "rabbitmq" : taskQueueBackend;
    }

    public Rabbitmq getRabbitmq() {
        return rabbitmq;
    }

    public void setRabbitmq(Rabbitmq rabbitmq) {
        this.rabbitmq = rabbitmq == null ? new Rabbitmq() : rabbitmq;
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

    public Payment getPayment() {
        return payment;
    }

    public void setPayment(Payment payment) {
        this.payment = payment == null ? new Payment() : payment;
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

    public static class Rabbitmq {
        private String taskExchange = "ai.task.exchange";
        private String taskRoutingKey = "tool.normal";
        private String taskQueue = "ai.tool.normal";
        private String deadQueue = "ai.tool.normal.dead";
        private String retryQueuePrefix = "ai.tool.normal.retry";
        private List<Integer> retryDelaysMs = new ArrayList<>(List.of(5000, 30000, 120000));

        public String getTaskExchange() {
            return taskExchange;
        }

        public void setTaskExchange(String taskExchange) {
            this.taskExchange = taskExchange == null || taskExchange.isBlank() ? "ai.task.exchange" : taskExchange;
        }

        public String getTaskRoutingKey() {
            return taskRoutingKey;
        }

        public void setTaskRoutingKey(String taskRoutingKey) {
            this.taskRoutingKey = taskRoutingKey == null || taskRoutingKey.isBlank() ? "tool.normal" : taskRoutingKey;
        }

        public String getTaskQueue() {
            return taskQueue;
        }

        public void setTaskQueue(String taskQueue) {
            this.taskQueue = taskQueue == null || taskQueue.isBlank() ? "ai.tool.normal" : taskQueue;
        }

        public String getDeadQueue() {
            return deadQueue;
        }

        public void setDeadQueue(String deadQueue) {
            this.deadQueue = deadQueue == null || deadQueue.isBlank() ? this.taskQueue + ".dead" : deadQueue;
        }

        public String getRetryQueuePrefix() {
            return retryQueuePrefix;
        }

        public void setRetryQueuePrefix(String retryQueuePrefix) {
            this.retryQueuePrefix = retryQueuePrefix == null || retryQueuePrefix.isBlank()
                    ? this.taskQueue + ".retry"
                    : retryQueuePrefix;
        }

        public List<Integer> getRetryDelaysMs() {
            return retryDelaysMs;
        }

        public void setRetryDelaysMs(List<Integer> retryDelaysMs) {
            this.retryDelaysMs = retryDelaysMs == null || retryDelaysMs.isEmpty()
                    ? new ArrayList<>(List.of(5000, 30000, 120000))
                    : retryDelaysMs;
        }
    }

    public static class Auth {
        private String cookieSameSite = "Lax";
        private Boolean cookieSecure;
        private Sms sms = new Sms();
        private Captcha captcha = new Captcha();

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

        public Captcha getCaptcha() {
            return captcha;
        }

        public void setCaptcha(Captcha captcha) {
            this.captcha = captcha == null ? new Captcha() : captcha;
        }
    }

    public static class Captcha {
        private boolean enabled = false;
        private String provider = "local";
        private String region = "cn";
        private String sceneId = "";
        private String endpoint = "";
        private String accessKeyId = "";
        private String accessKeySecret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null || provider.isBlank() ? "local" : provider;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region == null || region.isBlank() ? "cn" : region;
        }

        public String getSceneId() {
            return sceneId;
        }

        public void setSceneId(String sceneId) {
            this.sceneId = sceneId == null ? "" : sceneId;
        }

        public String getEndpoint() {
            if (endpoint != null && !endpoint.isBlank()) {
                return endpoint;
            }
            return "sgp".equalsIgnoreCase(region)
                    ? "captcha.ap-southeast-1.aliyuncs.com"
                    : "captcha.cn-shanghai.aliyuncs.com";
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint == null ? "" : endpoint;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId == null ? "" : accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret == null ? "" : accessKeySecret;
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
        private String aliyunAccessKeyId = "";
        private String aliyunAccessKeySecret = "";
        private String aliyunEndpoint = "dysmsapi.aliyuncs.com";
        private String aliyunSignName = "";
        private String aliyunTemplateCode = "";
        private String aliyunTemplateParamName = "code";

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

        public String getAliyunAccessKeyId() {
            return aliyunAccessKeyId;
        }

        public void setAliyunAccessKeyId(String aliyunAccessKeyId) {
            this.aliyunAccessKeyId = aliyunAccessKeyId == null ? "" : aliyunAccessKeyId;
        }

        public String getAliyunAccessKeySecret() {
            return aliyunAccessKeySecret;
        }

        public void setAliyunAccessKeySecret(String aliyunAccessKeySecret) {
            this.aliyunAccessKeySecret = aliyunAccessKeySecret == null ? "" : aliyunAccessKeySecret;
        }

        public String getAliyunEndpoint() {
            return aliyunEndpoint == null || aliyunEndpoint.isBlank() ? "dysmsapi.aliyuncs.com" : aliyunEndpoint;
        }

        public void setAliyunEndpoint(String aliyunEndpoint) {
            this.aliyunEndpoint = aliyunEndpoint == null || aliyunEndpoint.isBlank() ? "dysmsapi.aliyuncs.com" : aliyunEndpoint;
        }

        public String getAliyunSignName() {
            return aliyunSignName;
        }

        public void setAliyunSignName(String aliyunSignName) {
            this.aliyunSignName = aliyunSignName == null ? "" : aliyunSignName;
        }

        public String getAliyunTemplateCode() {
            return aliyunTemplateCode;
        }

        public void setAliyunTemplateCode(String aliyunTemplateCode) {
            this.aliyunTemplateCode = aliyunTemplateCode == null ? "" : aliyunTemplateCode;
        }

        public String getAliyunTemplateParamName() {
            return aliyunTemplateParamName == null || aliyunTemplateParamName.isBlank() ? "code" : aliyunTemplateParamName;
        }

        public void setAliyunTemplateParamName(String aliyunTemplateParamName) {
            this.aliyunTemplateParamName = aliyunTemplateParamName == null || aliyunTemplateParamName.isBlank()
                    ? "code"
                    : aliyunTemplateParamName;
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

    public static class Payment {
        private WechatNative wechatNative = new WechatNative();

        public WechatNative getWechatNative() {
            return wechatNative;
        }

        public void setWechatNative(WechatNative wechatNative) {
            this.wechatNative = wechatNative == null ? new WechatNative() : wechatNative;
        }
    }

    public static class WechatNative {
        private boolean enabled;
        private String appid = "";
        private String mchid = "";
        private String merchantSerialNo = "";
        private String merchantPrivateKeyPath = "";
        private String apiV3Key = "";
        private String wechatPayPublicKeyId = "";
        private String wechatPayPublicKeyPath = "";
        private String notifyUrl = "";
        private String apiBaseUrl = "https://api.mch.weixin.qq.com";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppid() {
            return appid;
        }

        public void setAppid(String appid) {
            this.appid = appid == null ? "" : appid;
        }

        public String getMchid() {
            return mchid;
        }

        public void setMchid(String mchid) {
            this.mchid = mchid == null ? "" : mchid;
        }

        public String getMerchantSerialNo() {
            return merchantSerialNo;
        }

        public void setMerchantSerialNo(String merchantSerialNo) {
            this.merchantSerialNo = merchantSerialNo == null ? "" : merchantSerialNo;
        }

        public String getMerchantPrivateKeyPath() {
            return merchantPrivateKeyPath;
        }

        public void setMerchantPrivateKeyPath(String merchantPrivateKeyPath) {
            this.merchantPrivateKeyPath = merchantPrivateKeyPath == null ? "" : merchantPrivateKeyPath;
        }

        public String getApiV3Key() {
            return apiV3Key;
        }

        public void setApiV3Key(String apiV3Key) {
            this.apiV3Key = apiV3Key == null ? "" : apiV3Key;
        }

        public String getWechatPayPublicKeyId() {
            return wechatPayPublicKeyId;
        }

        public void setWechatPayPublicKeyId(String wechatPayPublicKeyId) {
            this.wechatPayPublicKeyId = wechatPayPublicKeyId == null ? "" : wechatPayPublicKeyId;
        }

        public String getWechatPayPublicKeyPath() {
            return wechatPayPublicKeyPath;
        }

        public void setWechatPayPublicKeyPath(String wechatPayPublicKeyPath) {
            this.wechatPayPublicKeyPath = wechatPayPublicKeyPath == null ? "" : wechatPayPublicKeyPath;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl == null ? "" : notifyUrl;
        }

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                    ? "https://api.mch.weixin.qq.com"
                    : apiBaseUrl;
        }
    }
}
