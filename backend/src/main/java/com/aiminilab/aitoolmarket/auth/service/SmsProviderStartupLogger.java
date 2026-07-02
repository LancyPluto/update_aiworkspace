package com.aiminilab.aitoolmarket.auth.service;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SmsProviderStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmsProviderStartupLogger.class);

    private final AppProperties appProperties;

    public SmsProviderStartupLogger(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        log.info("SMS provider configured: provider={}, bmobApplicationIdConfigured={}, bmobRestApiKeyConfigured={}, aliyunAccessKeyConfigured={}, aliyunSignNameConfigured={}, aliyunTemplateCodeConfigured={}",
                sms.getProvider(),
                hasText(sms.getBmobApplicationId()),
                hasText(sms.getBmobRestApiKey()),
                hasText(sms.getAliyunAccessKeyId()) && hasText(sms.getAliyunAccessKeySecret()),
                hasText(sms.getAliyunSignName()),
                hasText(sms.getAliyunTemplateCode()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
