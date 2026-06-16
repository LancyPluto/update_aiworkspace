package com.aiminilab.aitoolmarket.subject.support;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SubjectVendorAccountResolver {

    public static final String DEFAULT_KLING_VENDOR_REF = "kling::默认账户";

    private final ModelVendorAccountMapper vendorAccountMapper;

    public SubjectVendorAccountResolver(ModelVendorAccountMapper vendorAccountMapper) {
        this.vendorAccountMapper = vendorAccountMapper;
    }

    public ModelVendorAccount resolve(String vendorAccountRef) {
        String normalizedRef = normalizeRef(vendorAccountRef);
        String[] parts = normalizedRef.split("::", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendorAccountRef 格式无效");
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveByVendorCodeAndAccountName(parts[0], parts[1]);
        if (account == null || Boolean.FALSE.equals(account.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "未找到可用的厂商账户：" + normalizedRef);
        }
        return account;
    }

    public String normalizeRef(String vendorAccountRef) {
        if (vendorAccountRef == null || vendorAccountRef.isBlank()) {
            return DEFAULT_KLING_VENDOR_REF;
        }
        return vendorAccountRef.trim();
    }
}
