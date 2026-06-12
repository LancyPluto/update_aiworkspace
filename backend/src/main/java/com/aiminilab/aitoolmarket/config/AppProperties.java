package com.aiminilab.aitoolmarket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private Cache cache = new Cache();
    private AssetStorage assetStorage = new AssetStorage();

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

    public AssetStorage getAssetStorage() {
        return assetStorage;
    }

    public void setAssetStorage(AssetStorage assetStorage) {
        this.assetStorage = assetStorage == null ? new AssetStorage() : assetStorage;
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

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache == null ? new Cache() : cache;
    }

    public static class Cache {
        private boolean enabled = true;
        private int defaultTtlSeconds = 300;
        private int toolTtlSeconds = 180;
        private int packageTtlSeconds = 600;
        private int settingsTtlSeconds = 300;
        private int vendorTtlSeconds = 300;
        private long evictDelayMs = 500;
        private int loadLockSeconds = 10;
        private int loadWaitMaxAttempts = 40;
        private long loadWaitIntervalMs = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultTtlSeconds() {
            return defaultTtlSeconds;
        }

        public void setDefaultTtlSeconds(int defaultTtlSeconds) {
            this.defaultTtlSeconds = defaultTtlSeconds < 1 ? 300 : defaultTtlSeconds;
        }

        public int getToolTtlSeconds() {
            return toolTtlSeconds;
        }

        public void setToolTtlSeconds(int toolTtlSeconds) {
            this.toolTtlSeconds = toolTtlSeconds < 1 ? 180 : toolTtlSeconds;
        }

        public int getPackageTtlSeconds() {
            return packageTtlSeconds;
        }

        public void setPackageTtlSeconds(int packageTtlSeconds) {
            this.packageTtlSeconds = packageTtlSeconds < 1 ? 600 : packageTtlSeconds;
        }

        public int getSettingsTtlSeconds() {
            return settingsTtlSeconds;
        }

        public void setSettingsTtlSeconds(int settingsTtlSeconds) {
            this.settingsTtlSeconds = settingsTtlSeconds < 1 ? 300 : settingsTtlSeconds;
        }

        public int getVendorTtlSeconds() {
            return vendorTtlSeconds;
        }

        public void setVendorTtlSeconds(int vendorTtlSeconds) {
            this.vendorTtlSeconds = vendorTtlSeconds < 1 ? 300 : vendorTtlSeconds;
        }

        public long getEvictDelayMs() {
            return evictDelayMs;
        }

        public void setEvictDelayMs(long evictDelayMs) {
            this.evictDelayMs = evictDelayMs < 0 ? 500 : evictDelayMs;
        }

        public int getLoadLockSeconds() {
            return loadLockSeconds;
        }

        public void setLoadLockSeconds(int loadLockSeconds) {
            this.loadLockSeconds = loadLockSeconds < 1 ? 10 : loadLockSeconds;
        }

        public int getLoadWaitMaxAttempts() {
            return loadWaitMaxAttempts;
        }

        public void setLoadWaitMaxAttempts(int loadWaitMaxAttempts) {
            this.loadWaitMaxAttempts = loadWaitMaxAttempts < 1 ? 40 : loadWaitMaxAttempts;
        }

        public long getLoadWaitIntervalMs() {
            return loadWaitIntervalMs;
        }

        public void setLoadWaitIntervalMs(long loadWaitIntervalMs) {
            this.loadWaitIntervalMs = loadWaitIntervalMs < 1 ? 50 : loadWaitIntervalMs;
        }
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
        private int maxHistoryMessages = 20;
        private String fileStorageDir = "data/agent-files";
        /** Base URL workers use to fetch /generated/* assets (e.g. http://backend:8080). */
        private String workerMediaBaseUrl = "http://127.0.0.1:8080";
        /** Optional bootstrap key for shiyunapi.com GPT-Image gateway (gpt-image-2_SY). */
        private String gptImageShiyunApiKey = "";
        /** Optional bootstrap key for api.ofox.ai GPT-Image gateway (GPT-image2.0). */
        private String gptImageOfoxApiKey = "";

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

        public int getMaxHistoryMessages() {
            return maxHistoryMessages;
        }

        public void setMaxHistoryMessages(int maxHistoryMessages) {
            this.maxHistoryMessages = maxHistoryMessages < 1 ? 20 : maxHistoryMessages;
        }

        public String getFileStorageDir() {
            return fileStorageDir;
        }

        public void setFileStorageDir(String fileStorageDir) {
            this.fileStorageDir = fileStorageDir == null || fileStorageDir.isBlank() ? "data/agent-files" : fileStorageDir;
        }

        public String getWorkerMediaBaseUrl() {
            return workerMediaBaseUrl;
        }

        public void setWorkerMediaBaseUrl(String workerMediaBaseUrl) {
            this.workerMediaBaseUrl = workerMediaBaseUrl == null || workerMediaBaseUrl.isBlank()
                    ? "http://127.0.0.1:8080"
                    : workerMediaBaseUrl;
        }

        public String getGptImageShiyunApiKey() {
            return gptImageShiyunApiKey;
        }

        public void setGptImageShiyunApiKey(String gptImageShiyunApiKey) {
            this.gptImageShiyunApiKey = gptImageShiyunApiKey == null ? "" : gptImageShiyunApiKey;
        }

        public String getGptImageOfoxApiKey() {
            return gptImageOfoxApiKey;
        }

        public void setGptImageOfoxApiKey(String gptImageOfoxApiKey) {
            this.gptImageOfoxApiKey = gptImageOfoxApiKey == null ? "" : gptImageOfoxApiKey;
        }
    }

    public static class Payment {
        private WechatNative wechatNative = new WechatNative();
        private AlipayPage alipayPage = new AlipayPage();

        public WechatNative getWechatNative() {
            return wechatNative;
        }

        public void setWechatNative(WechatNative wechatNative) {
            this.wechatNative = wechatNative == null ? new WechatNative() : wechatNative;
        }

        public AlipayPage getAlipayPage() {
            return alipayPage;
        }

        public void setAlipayPage(AlipayPage alipayPage) {
            this.alipayPage = alipayPage == null ? new AlipayPage() : alipayPage;
        }
    }

    public static class AlipayPage {
        private boolean enabled;
        private String appId = "";
        private String merchantPrivateKey = "";
        private String alipayPublicKey = "";
        private String notifyUrl = "";
        private String returnUrl = "";
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
        /** 固定为电脑网站支付 alipay.trade.page.pay */
        private String payMode = "PAGE";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId == null ? "" : appId;
        }

        public String getMerchantPrivateKey() {
            return merchantPrivateKey;
        }

        public void setMerchantPrivateKey(String merchantPrivateKey) {
            this.merchantPrivateKey = merchantPrivateKey == null ? "" : merchantPrivateKey;
        }

        public String getAlipayPublicKey() {
            return alipayPublicKey;
        }

        public void setAlipayPublicKey(String alipayPublicKey) {
            this.alipayPublicKey = alipayPublicKey == null ? "" : alipayPublicKey;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl == null ? "" : notifyUrl;
        }

        public String getReturnUrl() {
            return returnUrl;
        }

        public void setReturnUrl(String returnUrl) {
            this.returnUrl = returnUrl == null ? "" : returnUrl;
        }

        public String getGatewayUrl() {
            return gatewayUrl;
        }

        public void setGatewayUrl(String gatewayUrl) {
            this.gatewayUrl = gatewayUrl == null || gatewayUrl.isBlank()
                    ? "https://openapi.alipay.com/gateway.do"
                    : gatewayUrl;
        }

        public String getPayMode() {
            return payMode;
        }

        public void setPayMode(String payMode) {
            this.payMode = payMode == null || payMode.isBlank() ? "PAGE" : payMode.trim().toUpperCase(Locale.ROOT);
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

    /**
     * Unified asset storage: {@code local} (filesystem under {@link #generatedMediaDir}) or {@code oss} (Aliyun OSS).
     * Switch provider via {@code ASSET_STORAGE_PROVIDER}; secrets stay in environment variables only.
     */
    public static class AssetStorage {
        /** {@code local} or {@code oss}. */
        private String provider = "local";
        /**
         * Public URL prefix for stored assets.
         * Local: {@code /generated} (served by backend/nginx).
         * OSS: full base such as {@code https://bucket.oss-cn-hangzhou.aliyuncs.com/prod}.
         */
        private String publicBaseUrl = "/generated";
        private String ossEndpoint = "";
        private String ossBucket = "";
        private String ossAccessKeyId = "";
        private String ossAccessKeySecret = "";
        /** Optional key prefix inside the bucket, e.g. {@code prod/} or {@code dev/}. */
        private String ossKeyPrefix = "";

        public String getProvider() {
            return provider == null || provider.isBlank() ? "local" : provider.trim().toLowerCase(Locale.ROOT);
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl == null || publicBaseUrl.isBlank() ? "/generated" : publicBaseUrl.trim();
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getOssEndpoint() {
            return ossEndpoint == null ? "" : ossEndpoint.trim();
        }

        public void setOssEndpoint(String ossEndpoint) {
            this.ossEndpoint = ossEndpoint;
        }

        public String getOssBucket() {
            return ossBucket == null ? "" : ossBucket.trim();
        }

        public void setOssBucket(String ossBucket) {
            this.ossBucket = ossBucket;
        }

        public String getOssAccessKeyId() {
            return ossAccessKeyId == null ? "" : ossAccessKeyId.trim();
        }

        public void setOssAccessKeyId(String ossAccessKeyId) {
            this.ossAccessKeyId = ossAccessKeyId;
        }

        public String getOssAccessKeySecret() {
            return ossAccessKeySecret == null ? "" : ossAccessKeySecret.trim();
        }

        public void setOssAccessKeySecret(String ossAccessKeySecret) {
            this.ossAccessKeySecret = ossAccessKeySecret;
        }

        public String getOssKeyPrefix() {
            if (ossKeyPrefix == null || ossKeyPrefix.isBlank()) {
                return "";
            }
            String normalized = ossKeyPrefix.trim().replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            return normalized.endsWith("/") ? normalized : normalized + "/";
        }

        public void setOssKeyPrefix(String ossKeyPrefix) {
            this.ossKeyPrefix = ossKeyPrefix;
        }

        public boolean isOss() {
            return "oss".equals(getProvider());
        }
    }
}
