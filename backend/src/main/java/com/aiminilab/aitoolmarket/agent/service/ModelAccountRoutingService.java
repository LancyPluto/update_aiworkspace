package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelAccountRoutingRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.entity.ModelAccountRoutingPool;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelAccountRoutingService {
    private final ModelVendorAccountMapper accountMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final ModelAccountRoutingPoolService routingPoolService;
    private final VendorCodeResolver vendorCodeResolver;

    public ModelAccountRoutingService(ModelVendorAccountMapper accountMapper,
                                      AgentModelConfigMapper modelConfigMapper,
                                      ModelAccountRoutingPoolService routingPoolService,
                                      VendorCodeResolver vendorCodeResolver) {
        this.accountMapper = accountMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.routingPoolService = routingPoolService;
        this.vendorCodeResolver = vendorCodeResolver;
    }

    @Transactional
    public ModelVendorAccountResponse update(Long accountId, ModelAccountRoutingRequest request) {
        ModelVendorAccount account = accountMapper.findActiveByIdForUpdate(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "model vendor account not found");
        }
        int weight = normalizeWeight(request.loadBalanceWeight());
        String poolName = routingPoolService.normalizeName(request.routingPoolName());
        if (Boolean.TRUE.equals(request.loadBalanceEnabled()) && poolName == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "routingPoolName is required when load balancing is enabled");
        }
        ModelAccountRoutingPool targetPool = poolName == null
                ? null
                : routingPoolService.findByVendorAndName(account.getVendorCode(), poolName);
        Long targetPoolId = targetPool == null ? null : targetPool.getId();
        if (!java.util.Objects.equals(account.getRoutingPoolId(), targetPoolId)) {
            int incompatibleAnchors = targetPoolId == null
                    ? modelConfigMapper.countPoolBoundByVendorAccountId(accountId)
                    : modelConfigMapper.countPoolBoundOutsideRoutingPool(accountId, targetPoolId);
            if (incompatibleAnchors > 0) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "account is the anchor of a pool-bound model; update the model routing target first"
                );
            }
        }
        if (poolName != null && targetPool == null) {
            targetPool = routingPoolService.resolveOrCreate(account.getVendorCode(), poolName);
            targetPoolId = targetPool.getId();
        }
        if (accountMapper.updateRouting(
                accountId,
                Boolean.TRUE.equals(request.loadBalanceEnabled()),
                weight,
                targetPoolId
        ) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "model vendor account not found");
        }
        ModelVendorAccount updated = accountMapper.findActiveById(accountId);
        return ModelVendorAccountResponse.from(
                updated,
                vendorCodeResolver.vendorLabel(updated.getVendorCode()),
                accountMapper.countActiveModelsByAccountId(updated.getId())
        );
    }

    private int normalizeWeight(Integer value) {
        if (value == null || value < 1 || value > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "loadBalanceWeight must be between 1 and 100");
        }
        return value;
    }
}
