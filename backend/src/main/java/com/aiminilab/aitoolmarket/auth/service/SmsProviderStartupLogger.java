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
        log.info("SMS provider configured: provider={}, ihuyiApiId={}, ihuyiApiKeyConfigured={}, bmobApplicationIdConfigured={}, bmobRestApiKeyConfigured={}",
                sms.getProvider(),
                mask(sms.getIhuyiApiId()),
                hasText(sms.getIhuyiApiKey()),
                hasText(sms.getBmobApplicationId()),
                hasText(sms.getBmobRestApiKey()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String mask(String value) {
        if (!hasText(value)) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
