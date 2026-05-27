package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.service.impl.AliyunHumanCaptchaService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliyunHumanCaptchaServiceTest {

    @Test
    void skipsVerificationWhenDisabled() {
        AppProperties properties = new AppProperties();
        properties.getAuth().getCaptcha().setEnabled(false);

        assertThatCode(() -> new AliyunHumanCaptchaService(properties).verify(null))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresCaptchaVerifyParamWhenAliyunCaptchaEnabled() {
        AppProperties properties = new AppProperties();
        properties.getAuth().getCaptcha().setEnabled(true);
        properties.getAuth().getCaptcha().setProvider("aliyun");

        assertThatThrownBy(() -> new AliyunHumanCaptchaService(properties).verify(""))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
    }
}
