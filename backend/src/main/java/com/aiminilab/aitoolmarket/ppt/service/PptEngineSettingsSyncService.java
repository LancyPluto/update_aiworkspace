package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class PptEngineSettingsSyncService {

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final PptEngineClient pptEngineClient;

    public PptEngineSettingsSyncService(AgentModelConfigMapper agentModelConfigMapper,
                                        PptEngineClient pptEngineClient) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.pptEngineClient = pptEngineClient;
    }

    public void syncFromWorkflow(PptWorkflow workflow) {
        if (workflow == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "workflow 不能为空");
        }
        Long textId = workflow.getTextModelConfigId();
        Long imageId = workflow.getImageModelConfigId();
        boolean hasModel = textId != null || imageId != null;
        boolean hasEngineSecret = workflow.getEngineSecrets() != null
                && workflow.getEngineSecrets().values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!hasModel && !hasEngineSecret) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请至少配置一项大模型绑定或引擎 API（如 MinerU、百度 OCR）");
        }
        AgentModelConfig text = textId == null ? null : requireEnabledConfig(textId, "文本");
        AgentModelConfig image = imageId == null ? null : requireEnabledConfig(imageId, "生图");
        Map<String, Object> payload = PptBananaSettingsMapper.toBananaSettings(text, image);
        PptEngineSecretSupport.mergeEngineSecretsIntoPayload(payload, workflow.getEngineSecrets());
        pptEngineClient.updateSettings(payload);
    }

    private AgentModelConfig requireEnabledConfig(Long id, String label) {
        AgentModelConfig config = agentModelConfigMapper.findActiveById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型配置不存在: " + id);
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型配置已禁用: " + id);
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型配置缺少 API Key: " + id);
        }
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "模型配置缺少模型名称: " + id);
        }
        return config;
    }
}
