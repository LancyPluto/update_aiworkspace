package com.aiminilab.aitoolmarket.auth.service.impl;

import com.aiminilab.aitoolmarket.auth.service.HumanCaptchaService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aliyun.captcha20230305.Client;
import com.aliyun.captcha20230305.models.VerifyIntelligentCaptchaRequest;
import com.aliyun.captcha20230305.models.VerifyIntelligentCaptchaResponse;
import com.aliyun.captcha20230305.models.VerifyIntelligentCaptchaResponseBody;
import com.aliyun.teaopenapi.models.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AliyunHumanCaptchaService implements HumanCaptchaService {

    private static final Logger log = LoggerFactory.getLogger(AliyunHumanCaptchaService.class);

    private final AppProperties appProperties;
    private volatile Client client;

    public AliyunHumanCaptchaService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void verify(String captchaVerifyParam) {
        AppProperties.Captcha captcha = appProperties.getAuth().getCaptcha();
        if (!captcha.isEnabled()) {
            return;
        }
        if (!"aliyun".equalsIgnoreCase(captcha.getProvider())) {
            return;
        }
        if (!hasText(captchaVerifyParam)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请先完成人机验证码");
        }
        if (!hasText(captcha.getAccessKeyId()) || !hasText(captcha.getAccessKeySecret()) || !hasText(captcha.getSceneId())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "阿里云验证码配置不完整");
        }
        try {
            VerifyIntelligentCaptchaRequest request = new VerifyIntelligentCaptchaRequest()
                    .setCaptchaVerifyParam(captchaVerifyParam)
                    .setSceneId(captcha.getSceneId());
            VerifyIntelligentCaptchaResponse response = getClient(captcha).verifyIntelligentCaptcha(request);
            VerifyIntelligentCaptchaResponseBody body = response.getBody();
            VerifyIntelligentCaptchaResponseBody.VerifyIntelligentCaptchaResponseBodyResult result =
                    body == null ? null : body.getResult();
            if (body == null || result == null || !Boolean.TRUE.equals(result.getVerifyResult())) {
                String verifyCode = result == null ? null : result.getVerifyCode();
                log.warn("Aliyun captcha rejected: requestId={}, code={}, message={}, verifyCode={}",
                        body == null ? null : body.getRequestId(),
                        body == null ? null : body.getCode(),
                        body == null ? null : body.getMessage(),
                        verifyCode);
                throw new BusinessException(ErrorCode.PARAM_ERROR, "人机验证码校验失败，请重新验证");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Aliyun captcha verify failed", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "阿里云验证码服务暂不可用");
        }
    }

    private Client getClient(AppProperties.Captcha captcha) throws Exception {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                Config config = new Config()
                        .setAccessKeyId(captcha.getAccessKeyId())
                        .setAccessKeySecret(captcha.getAccessKeySecret())
                        .setEndpoint(captcha.getEndpoint());
                client = new Client(config);
            }
            return client;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
