package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.service.impl.SmsCodeServiceImpl;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AliyunSmsCodeServiceTest {

    @Test
    void aliyunProviderRequiresCompleteConfiguration() {
        AppProperties properties = new AppProperties();
        properties.getAuth().getSms().setProvider("aliyun");

        SmsCodeServiceImpl service = new SmsCodeServiceImpl(mock(StringRedisTemplate.class), properties);

        assertThatThrownBy(() -> service.sendCode("13376644413", "LOGIN"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR))
                .hasMessageContaining("阿里云短信配置不完整");
    }
}
