package com.aiminilab.aitoolmarket.commerce.payment;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentProviderRegistry {

    private final Map<String, PaymentProvider> providers;

    public PaymentProviderRegistry(List<PaymentProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(provider -> normalize(provider.channel()), Function.identity()));
    }

    public PaymentProvider require(String channel) {
        PaymentProvider provider = providers.get(normalize(channel));
        if (provider == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported payment channel");
        }
        return provider;
    }

    private static String normalize(String channel) {
        return channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
    }
}
