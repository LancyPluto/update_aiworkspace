package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import org.springframework.stereotype.Service;

@Service
public class PptWorkbenchGuard {
    private final PptEngineProperties properties;

    public PptWorkbenchGuard(PptEngineProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isWorkbenchEnabled();
    }

    public void requireGenerationEnabled() {
        if (!enabled()) {
            throw new BusinessException(
                    ErrorCode.PPT_ENGINE_ERROR,
                    "PPT 工作台维护中，暂不可新建或生成；已有项目与文件仍可查看下载"
            );
        }
    }
}
