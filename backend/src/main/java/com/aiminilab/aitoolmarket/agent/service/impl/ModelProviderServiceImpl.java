package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderMetadataService;
import com.aiminilab.aitoolmarket.agent.service.ModelProviderService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderMetadataService metadataService;

    public ModelProviderServiceImpl(ModelProviderMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public List<ModelProviderResponse> list(String capability) {
        return metadataService.list(capability);
    }

    @Override
    public ModelProviderResponse get(String code) {
        return metadataService.get(code);
    }
}
