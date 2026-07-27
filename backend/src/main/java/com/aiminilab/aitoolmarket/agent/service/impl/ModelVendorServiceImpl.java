package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.cache.CacheNamespaces;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiminilab.aitoolmarket.agent.dto.UpsertModelVendorRequest;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorService;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ModelVendorServiceImpl implements ModelVendorService {

    private final ModelVendorMapper modelVendorMapper;
    private final ModelVendorAccountMapper modelVendorAccountMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ToolMapper toolMapper;
    private final BypassCacheService bypassCacheService;
    private final ObjectMapper objectMapper;
    private final VendorCodeResolver vendorCodeResolver;

    public ModelVendorServiceImpl(ModelVendorMapper modelVendorMapper,
                                  ModelVendorAccountMapper modelVendorAccountMapper,
                                  AgentModelConfigMapper agentModelConfigMapper,
                                  ToolMapper toolMapper,
                                  BypassCacheService bypassCacheService,
                                  ObjectMapper objectMapper,
                                  VendorCodeResolver vendorCodeResolver) {
        this.modelVendorMapper = modelVendorMapper;
        this.modelVendorAccountMapper = modelVendorAccountMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.toolMapper = toolMapper;
        this.bypassCacheService = bypassCacheService;
        this.objectMapper = objectMapper;
        this.vendorCodeResolver = vendorCodeResolver;
    }

    @Override
    public List<ModelVendorResponse> listEnabled() {
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, ModelVendorResponse.class);
        List<ModelVendorResponse> cached = bypassCacheService.getOrLoad(
                CacheNamespaces.MODEL_VENDORS_ENABLED,
                bypassCacheService.vendorTtl(),
                type,
                () -> modelVendorMapper.findAllEnabled().stream()
                        .map(ModelVendorResponse::from)
                        .toList()
        );
        return cached.stream()
                .filter(vendor -> !vendorCodeResolver.isDeprecatedVirtualVendorCode(vendor.vendorCode()))
                .toList();
    }

    @Override
    public List<ModelVendorResponse> adminListAll() {
        return modelVendorMapper.selectList(null).stream()
                .filter(vendor -> !vendorCodeResolver.isDeprecatedVirtualVendorCode(vendor.getVendorCode()))
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
        String vendorCode = vendorCodeResolver.requireConcreteVendorCode(request.vendorCode());
        ModelVendor existing = modelVendorMapper.findByCode(vendorCode);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ModelVendor vendor = new ModelVendor();
            vendor.setVendorCode(vendorCode);
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

    @Override
    @Transactional
    public void adminDelete(String vendorCode, Long operatorId) {
        String normalized = vendorCode == null ? "" : vendorCode.trim().toLowerCase();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("vendorCode is required");
        }
        Long safeOperatorId = operatorId == null ? 0L : operatorId;
        toolMapper.softDeleteByVendorCode(normalized, safeOperatorId);
        agentModelConfigMapper.softDeleteByVendorCode(normalized);
        modelVendorAccountMapper.softDeleteByVendorCode(normalized);
        modelVendorMapper.disableByCode(normalized);
        bypassCacheService.invalidateModelVendors();
    }
}

