package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendor;

public record ModelVendorResponse(
        String vendorCode,
        String vendorLabel,
        String iconAsset,
        Integer sortOrder,
        Boolean enabled
) {
    public static ModelVendorResponse from(ModelVendor vendor) {
        if (vendor == null) {
            return null;
        }
        return new ModelVendorResponse(
                vendor.getVendorCode(),
                vendor.getVendorLabel(),
                vendor.getIconAsset(),
                vendor.getSortOrder(),
                vendor.getEnabled()
        );
    }
}

