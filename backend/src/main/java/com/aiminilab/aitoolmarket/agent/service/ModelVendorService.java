package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpsertModelVendorRequest;

import java.util.List;

public interface ModelVendorService {
    List<ModelVendorResponse> listEnabled();
    List<ModelVendorResponse> adminListAll();
    ModelVendorResponse adminUpsert(UpsertModelVendorRequest request);
}

