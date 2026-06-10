package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountDiscoveryResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;

import java.util.List;

public interface ModelVendorAccountService {

    List<ModelVendorAccountResponse> adminList(String vendorCode);

    ModelVendorAccountResponse adminGet(Long id);

    ModelVendorAccountResponse adminCreate(ModelVendorAccountRequest request);

    ModelVendorAccountResponse adminUpdate(Long id, ModelVendorAccountRequest request);

    void adminDelete(Long id);

    ModelVendorAccountResponse adminRefreshBalance(Long id);

    int adminRefreshBalanceAll();

    ModelVendorAccountTestResponse adminTest(Long id);

    ModelVendorAccountDiscoveryResponse adminDiscoverModels(Long id);
}
