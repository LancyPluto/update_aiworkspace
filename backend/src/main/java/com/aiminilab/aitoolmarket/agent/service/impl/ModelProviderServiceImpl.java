package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderRegistry providerRegistry;

    public ModelProviderServiceImpl(ModelProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @Override
    public List<ModelProviderResponse> list(String capability) {
        return providerRegistry.listByCapability(capability).stream()
                .map(ModelProviderResponse::from)
                .toList();
    }

    @Override
    public ModelProviderResponse get(String code) {
        ModelProviderDefinition definition = providerRegistry.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "model provider not found"));
        return ModelProviderResponse.from(definition);
    }
}
