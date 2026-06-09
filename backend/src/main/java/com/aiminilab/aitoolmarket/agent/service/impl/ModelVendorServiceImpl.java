package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.cache.CacheNamespaces;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiminilab.aitoolmarket.agent.dto.UpsertModelVendorRequest;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ModelVendorServiceImpl implements ModelVendorService {

    private final ModelVendorMapper modelVendorMapper;
    private final BypassCacheService bypassCacheService;
    private final ObjectMapper objectMapper;

    public ModelVendorServiceImpl(ModelVendorMapper modelVendorMapper,
                                  BypassCacheService bypassCacheService,
                                  ObjectMapper objectMapper) {
        this.modelVendorMapper = modelVendorMapper;
        this.bypassCacheService = bypassCacheService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ModelVendorResponse> listEnabled() {
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, ModelVendorResponse.class);
        return bypassCacheService.getOrLoad(
                CacheNamespaces.MODEL_VENDORS_ENABLED,
                bypassCacheService.vendorTtl(),
                type,
                () -> modelVendorMapper.findAllEnabled().stream()
                        .map(ModelVendorResponse::from)
                        .toList()
        );
    }

    @Override
    public List<ModelVendorResponse> adminListAll() {
        return modelVendorMapper.selectList(null).stream()
                .sorted(Comparator
                        .comparing((ModelVendor vendor) -> Boolean.FALSE.equals(vendor.getEnabled()) ? 1 : 0)
                        .thenComparing(vendor -> vendor.getSortOrder() == null ? 0 : vendor.getSortOrder())
                        .thenComparing(ModelVendor::getVendorCode))
                .map(ModelVendorResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public ModelVendorResponse adminUpsert(UpsertModelVendorRequest request) {
        ModelVendor existing = modelVendorMapper.findByCode(request.vendorCode());
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ModelVendor vendor = new ModelVendor();
            vendor.setVendorCode(request.vendorCode());
            vendor.setVendorLabel(request.vendorLabel());
            vendor.setIconAsset(request.iconAsset());
            vendor.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
            vendor.setEnabled(Boolean.TRUE.equals(request.enabled()));
            vendor.setCreatedAt(now);
            vendor.setUpdatedAt(now);
            modelVendorMapper.insert(vendor);
            bypassCacheService.invalidateModelVendors();
            return ModelVendorResponse.from(vendor);
        }
        existing.setVendorLabel(request.vendorLabel());
        existing.setIconAsset(request.iconAsset());
        existing.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        existing.setEnabled(Boolean.TRUE.equals(request.enabled()));
        existing.setUpdatedAt(now);
        modelVendorMapper.updateById(existing);
        bypassCacheService.invalidateModelVendors();
        return ModelVendorResponse.from(existing);
    }
}

