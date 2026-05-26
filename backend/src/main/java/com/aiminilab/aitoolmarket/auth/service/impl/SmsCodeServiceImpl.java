package com.aiminilab.aitoolmarket.auth.service.impl;

import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.service.SmsCodeService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SmsCodeServiceImpl implements SmsCodeService {

    private static final Logger log = LoggerFactory.getLogger(SmsCodeServiceImpl.class);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final String KEY_PREFIX = "auth:sms:code:";
    private static final String COOLDOWN_PREFIX = "auth:sms:cooldown:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Map<String, LocalCode> localCodes = new ConcurrentHashMap<>();
    private final Map<String, Instant> localCooldowns = new ConcurrentHashMap<>();
    private volatile Client aliyunClient;

    public SmsCodeServiceImpl(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.appProperties = appProperties;
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public SmsCodeResponse sendCode(String phone, String scene) {
        String normalizedScene = normalizeScene(scene);
        String cooldownKey = cooldownKey(phone, normalizedScene);
        assertNotCoolingDown(cooldownKey);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        if (providerIs("aliyun")) {
            requestAliyunSmsCode(phone, code);
            storeLocalCode(codeKey(phone, normalizedScene), cooldownKey, code);
            return new SmsCodeResponse((int) CODE_TTL.toSeconds(), (int) SEND_COOLDOWN.toSeconds(), null);
        }

        if (useIhuyi()) {
            requestIhuyiSmsCode(phone, code);
            storeLocalCode(codeKey(phone, normalizedScene), cooldownKey, code);
            return new SmsCodeResponse((int) CODE_TTL.toSeconds(), (int) SEND_COOLDOWN.toSeconds(), null);
        }

        if (useBmob()) {
            requestBmobSmsCode(phone);
            storeCooldown(cooldownKey);
            return new SmsCodeResponse((int) CODE_TTL.toSeconds(), (int) SEND_COOLDOWN.toSeconds(), null);
        }

        storeLocalCode(codeKey(phone, normalizedScene), cooldownKey, code);
        log.info("SMS verification code generated locally: phone={}, scene={}, code={}", phone, normalizedScene, code);
        String debugCode = appProperties.isProductionMode() ? null : code;
        return new SmsCodeResponse((int) CODE_TTL.toSeconds(), (int) SEND_COOLDOWN.toSeconds(), debugCode);
    }

    @Override
    public void verifyCode(String phone, String scene, String code) {
        String normalizedScene = normalizeScene(scene);
        if (useBmob()) {
            verifyBmobSmsCode(phone, code);
            return;
        }

        String key = codeKey(phone, normalizedScene);
        String savedCode = readCode(key);
        if (savedCode == null || !savedCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码错误或已过期");
        }
        deleteCode(key);
    }

    private boolean useIhuyi() {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        return "ihuyi".equalsIgnoreCase(sms.getProvider()) && hasText(sms.getIhuyiApiId()) && hasText(sms.getIhuyiApiKey());
    }

    private boolean useBmob() {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        return "bmob".equalsIgnoreCase(sms.getProvider()) && hasText(sms.getBmobApplicationId()) && hasText(sms.getBmobRestApiKey());
    }

    private boolean providerIs(String provider) {
        return provider.equalsIgnoreCase(appProperties.getAuth().getSms().getProvider());
    }

    private void requestAliyunSmsCode(String phone, String code) {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        assertAliyunConfigured(sms);
        try {
            String templateParam = objectMapper.writeValueAsString(Map.of(sms.getAliyunTemplateParamName(), code));
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(sms.getAliyunSignName())
                    .setTemplateCode(sms.getAliyunTemplateCode())
                    .setTemplateParam(templateParam);
            SendSmsResponse response = aliyunClient(sms).sendSms(request);
            SendSmsResponseBody body = response == null ? null : response.getBody();
            if (body == null || !"OK".equalsIgnoreCase(body.getCode())) {
                String message = body == null ? "empty response" : body.getMessage();
                log.warn("Aliyun SMS request failed: requestId={}, code={}, message={}",
                        body == null ? null : body.getRequestId(),
                        body == null ? null : body.getCode(),
                        message);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败：" + message);
            }
            log.info("Aliyun SMS code requested: phone={}, bizId={}", phone, body.getBizId());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Aliyun SMS request failed", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败");
        }
    }

    private void assertAliyunConfigured(AppProperties.Sms sms) {
        if (!hasText(sms.getAliyunAccessKeyId())
                || !hasText(sms.getAliyunAccessKeySecret())
                || !hasText(sms.getAliyunSignName())
                || !hasText(sms.getAliyunTemplateCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "阿里云短信配置不完整");
        }
    }

    private Client aliyunClient(AppProperties.Sms sms) throws Exception {
        Client existing = aliyunClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (aliyunClient == null) {
                Config config = new Config()
                        .setAccessKeyId(sms.getAliyunAccessKeyId())
                        .setAccessKeySecret(sms.getAliyunAccessKeySecret())
                        .setEndpoint(sms.getAliyunEndpoint());
                aliyunClient = new Client(config);
            }
            return aliyunClient;
        }
    }

    @SuppressWarnings("unchecked")
    private void requestIhuyiSmsCode(String phone, String code) {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("account", sms.getIhuyiApiId());
        body.add("password", sms.getIhuyiApiKey());
        body.add("mobile", phone);
        body.add("templateid", sms.getIhuyiTemplateId());
        body.add("content", code);
        body.add("format", "json");

        try {
            Map<String, Object> response = restClient.post()
                    .uri(sms.getIhuyiBaseUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object codeValue = response == null ? null : response.get("code");
            if (!"2".equals(String.valueOf(codeValue))) {
                Object message = response == null ? null : response.get("msg");
                log.warn("Ihuyi SMS request failed: response={}", response);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败：" + (message == null ? "平台返回异常" : message));
            }
            log.info("Ihuyi SMS code requested: phone={}", phone);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("Ihuyi SMS request failed: status={}, body={}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败");
        } catch (RuntimeException exception) {
            log.warn("Ihuyi SMS request failed", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败");
        }
    }

    private void requestBmobSmsCode(String phone) {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        Map<String, Object> body = new HashMap<>();
        body.put("mobilePhoneNumber", phone);
        if (hasText(sms.getBmobTemplate())) {
            body.put("template", sms.getBmobTemplate().trim());
        }

        try {
            restClient.post()
                    .uri(baseUrl() + "/1/requestSmsCode")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Bmob-Application-Id", sms.getBmobApplicationId())
                    .header("X-Bmob-REST-API-Key", sms.getBmobRestApiKey())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Bmob SMS code requested: phone={}", phone);
        } catch (RestClientResponseException exception) {
            log.warn("Bmob SMS request failed: status={}, body={}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败");
        } catch (RuntimeException exception) {
            log.warn("Bmob SMS request failed", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "短信验证码发送失败");
        }
    }

    private void verifyBmobSmsCode(String phone, String code) {
        AppProperties.Sms sms = appProperties.getAuth().getSms();
        try {
            restClient.post()
                    .uri(baseUrl() + "/1/verifySmsCode/" + code)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Bmob-Application-Id", sms.getBmobApplicationId())
                    .header("X-Bmob-REST-API-Key", sms.getBmobRestApiKey())
                    .body(Map.of("mobilePhoneNumber", phone))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            log.warn("Bmob SMS verify failed: status={}, body={}", exception.getStatusCode(), exception.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码错误或已过期");
        } catch (RuntimeException exception) {
            log.warn("Bmob SMS verify failed", exception);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码错误或已过期");
        }
    }

    private String baseUrl() {
        String baseUrl = appProperties.getAuth().getSms().getBmobBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private void storeLocalCode(String key, String cooldownKey, String code) {
        try {
            redisTemplate.opsForValue().set(key, code, CODE_TTL);
            redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN);
        } catch (RuntimeException exception) {
            localCodes.put(key, new LocalCode(code, Instant.now().plus(CODE_TTL)));
            localCooldowns.put(cooldownKey, Instant.now().plus(SEND_COOLDOWN));
            log.warn("Redis unavailable while storing SMS code; using local fallback");
        }
    }

    private void storeCooldown(String cooldownKey) {
        try {
            redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN);
        } catch (RuntimeException exception) {
            localCooldowns.put(cooldownKey, Instant.now().plus(SEND_COOLDOWN));
            log.warn("Redis unavailable while storing SMS cooldown; using local fallback");
        }
    }

    private void assertNotCoolingDown(String cooldownKey) {
        Instant localExpiresAt = localCooldowns.get(cooldownKey);
        if (localExpiresAt != null) {
            if (Instant.now().isBefore(localExpiresAt)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码发送过于频繁，请稍后再试");
            }
            localCooldowns.remove(cooldownKey, localExpiresAt);
        }
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码发送过于频繁，请稍后再试");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while checking SMS cooldown; using local fallback");
        }
    }

    private String readCode(String key) {
        LocalCode localCode = localCodes.get(key);
        if (localCode != null) {
            if (Instant.now().isBefore(localCode.expiresAt())) {
                return localCode.code();
            }
            localCodes.remove(key, localCode);
        }
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while reading SMS code; using local fallback only");
            return null;
        }
    }

    private void deleteCode(String key) {
        localCodes.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable while deleting SMS code");
        }
    }

    private String normalizeScene(String scene) {
        String normalized = scene == null ? "" : scene.trim().toUpperCase(Locale.ROOT);
        if (!"REGISTER".equals(normalized)
                && !"LOGIN".equals(normalized)
                && !"LOGIN_OR_REGISTER".equals(normalized)
                && !"RESET_PASSWORD".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码场景不正确");
        }
        return normalized;
    }

    private String codeKey(String phone, String scene) {
        return KEY_PREFIX + scene + ":" + phone;
    }

    private String cooldownKey(String phone, String scene) {
        return COOLDOWN_PREFIX + scene + ":" + phone;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record LocalCode(String code, Instant expiresAt) {
    }
}
