package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ModelVendorResponse;
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

    public ModelVendorServiceImpl(ModelVendorMapper modelVendorMapper) {
        this.modelVendorMapper = modelVendorMapper;
    }

    @Override
    public List<ModelVendorResponse> listEnabled() {
        return modelVendorMapper.findAllEnabled().stream()
                .map(ModelVendorResponse::from)
                .toList();
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
            return ModelVendorResponse.from(vendor);
        }
        existing.setVendorLabel(request.vendorLabel());
        existing.setIconAsset(request.iconAsset());
        existing.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        existing.setEnabled(Boolean.TRUE.equals(request.enabled()));
        existing.setUpdatedAt(now);
        modelVendorMapper.updateById(existing);
        return ModelVendorResponse.from(existing);
    }
}

