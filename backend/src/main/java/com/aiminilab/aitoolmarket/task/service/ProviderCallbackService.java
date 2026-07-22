package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackEventResponse;
import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackRegistrationRequest;
import com.aiminilab.aitoolmarket.task.dto.ProviderCallbackRegistrationResponse;
import com.fasterxml.jackson.databind.JsonNode;

public interface ProviderCallbackService {
    ProviderCallbackRegistrationResponse register(Long taskId, ProviderCallbackRegistrationRequest request);

    ProviderCallbackEventResponse latest(Long taskId, String providerCode, String claimToken);

    void receiveSuno(String callbackToken, JsonNode payload);
}
