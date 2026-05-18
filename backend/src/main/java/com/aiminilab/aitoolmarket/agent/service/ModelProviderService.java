package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;

import java.util.List;

public interface ModelProviderService {
    List<ModelProviderResponse> list(String capability);

    ModelProviderResponse get(String code);
}
