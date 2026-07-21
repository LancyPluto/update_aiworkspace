package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.ModelAccountRoutingPool;
import com.aiminilab.aitoolmarket.agent.mapper.ModelAccountRoutingPoolMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ModelAccountRoutingPoolService {
    private static final int MAX_POOL_NAME_LENGTH = 128;

    private final ModelAccountRoutingPoolMapper poolMapper;

    public ModelAccountRoutingPoolService(ModelAccountRoutingPoolMapper poolMapper) {
        this.poolMapper = poolMapper;
    }

    public ModelAccountRoutingPool resolveOrCreate(String vendorCode, String requestedPoolName) {
        String poolName = normalizeName(requestedPoolName);
        if (poolName == null) {
            return null;
        }
        if (vendorCode == null || vendorCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor code is required for routing pool");
        }
        String normalizedVendorCode = vendorCode.trim();
        String poolKey = poolName.toLowerCase(Locale.ROOT);
        poolMapper.upsert(normalizedVendorCode, poolName, poolKey);
        ModelAccountRoutingPool pool = poolMapper.findByVendorCodeAndPoolKey(normalizedVendorCode, poolKey);
        if (pool == null) {
            throw new IllegalStateException("routing pool upsert did not return a row");
        }
        return pool;
    }

    public ModelAccountRoutingPool findByVendorAndName(String vendorCode, String requestedPoolName) {
        String poolName = normalizeName(requestedPoolName);
        if (poolName == null || vendorCode == null || vendorCode.isBlank()) {
            return null;
        }
        return poolMapper.findByVendorCodeAndPoolKey(
                vendorCode.trim(),
                poolName.toLowerCase(Locale.ROOT)
        );
    }

    public String normalizeName(String requestedPoolName) {
        if (requestedPoolName == null || requestedPoolName.trim().isEmpty()) {
            return null;
        }
        String poolName = requestedPoolName.trim();
        if (poolName.length() > MAX_POOL_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "routingPoolName must not exceed 128 characters");
        }
        return poolName;
    }
}
