package com.aiminilab.aitoolmarket.ppt.engine;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PptEngineRegistry {
    private final Map<String, PptEngineAdapter> adapters;

    public PptEngineRegistry(List<PptEngineAdapter> adapters) {
        Map<String, PptEngineAdapter> registered = new LinkedHashMap<>();
        for (PptEngineAdapter adapter : adapters) {
            if (registered.putIfAbsent(adapter.engineCode(), adapter) != null) {
                throw new IllegalStateException("Duplicate PPT engine adapter: " + adapter.engineCode());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    public PptEngineAdapter require(String engineCode) {
        PptEngineAdapter adapter = adapters.get(engineCode);
        if (adapter == null) {
            throw new BusinessException(ErrorCode.PPT_ENGINE_ERROR, "PPT 引擎未配置: " + engineCode);
        }
        return adapter;
    }

    public List<EngineCapabilities> capabilities() {
        return adapters.values().stream()
                .map(PptEngineAdapter::capabilities)
                .toList();
    }
}
