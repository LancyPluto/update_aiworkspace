package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentSkillBundleResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSkillDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentSkillBundleRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentSkillBundle;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSkillBundleMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentSkillBundleService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class AgentSkillBundleServiceImpl implements AgentSkillBundleService {
    private final AgentSkillBundleMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentSkillBundleServiceImpl(AgentSkillBundleMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentSkillBundleResponse> listAdmin() {
        return mapper.selectLatestAll().stream().map(this::toResponse).toList();
    }

    @Override
    public AgentSkillBundleResponse getAdmin(String skillCode) {
        AgentSkillBundle bundle = mapper.selectLatestBySkillCode(skillCode);
        if (bundle == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill Bundle 不存在");
        }
        return toResponse(bundle);
    }

    @Override
    @Transactional
    public AgentSkillBundleResponse saveDraft(String skillCode, UpdateAgentSkillBundleRequest request) {
        AgentSkillBundle latest = mapper.selectLatestBySkillCode(skillCode);
        // 已有草稿就原地更新；最新版本已发布时，复制配置并创建下一个版本的草稿。
        AgentSkillBundle draft = latest != null && "DRAFT".equalsIgnoreCase(latest.getStatus())
                ? latest
                : cloneAsNextDraft(skillCode, latest);
        applyRequest(draft, request);
        draft.setStatus("DRAFT");
        draft.setUpdatedAt(LocalDateTime.now());
        if (draft.getId() == null) {
            draft.setCreatedAt(LocalDateTime.now());
            mapper.insert(draft);
        } else {
            mapper.updateById(draft);
        }
        return toResponse(draft);
    }

    @Override
    @Transactional
    public AgentSkillBundleResponse publish(String skillCode) {
        AgentSkillBundle latest = mapper.selectLatestBySkillCode(skillCode);
        if (latest == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill Bundle 不存在");
        }
        // 一个 skillCode 只保留一个线上版本，历史已发布版本转为 ARCHIVED 以便审计和回溯。
        mapper.selectList(new LambdaQueryWrapper<AgentSkillBundle>()
                .eq(AgentSkillBundle::getSkillCode, skillCode)
                .eq(AgentSkillBundle::getStatus, "PUBLISHED"))
                .forEach(item -> {
                    item.setStatus("ARCHIVED");
                    item.setUpdatedAt(LocalDateTime.now());
                    mapper.updateById(item);
                });
        latest.setStatus("PUBLISHED");
        latest.setPublishedAt(LocalDateTime.now());
        latest.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(latest);
        return toResponse(latest);
    }

    @Override
    public AgentSkillBundleResponse getPublished(String skillCode) {
        AgentSkillBundle bundle = mapper.selectPublishedBySkillCode(skillCode);
        if (bundle == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "已发布 Skill Bundle 不存在");
        }
        return toResponse(bundle);
    }

    @Override
    public List<AgentSkillDescriptorResponse> listAvailableSkillDescriptors(List<AgentToolDescriptorResponse> availableTools) {
        // 首次构建 Agent Run Context 时只下发 Skill 摘要；完整 SOP 等模型选中工具后再按需读取。
        Set<String> visibleToolCodes = new LinkedHashSet<>();
        if (availableTools != null) {
            for (AgentToolDescriptorResponse tool : availableTools) {
                if (tool != null && tool.toolCode() != null && !tool.toolCode().isBlank()) {
                    visibleToolCodes.add(tool.toolCode());
                }
            }
        }
        if (visibleToolCodes.isEmpty()) {
            return List.of();
        }
        List<AgentSkillDescriptorResponse> result = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (AgentSkillBundle bundle : mapper.selectPublished()) {
            if (!emitted.add(bundle.getSkillCode())) {
                continue;
            }
            List<String> toolCodes = readStringList(bundle.getToolCodesJson());
            boolean matched = visibleToolCodes.stream().anyMatch(code -> matchesAny(code, toolCodes));
            if (!matched) {
                continue;
            }
            result.add(new AgentSkillDescriptorResponse(
                    bundle.getSkillCode(),
                    nullToEmpty(bundle.getDisplayName()),
                    nullToEmpty(bundle.getDescription()),
                    toolCodes,
                    bundle.getVersion()
            ));
        }
        return result;
    }

    private AgentSkillBundle cloneAsNextDraft(String skillCode, AgentSkillBundle latest) {
        AgentSkillBundle draft = new AgentSkillBundle();
        draft.setSkillCode(skillCode);
        draft.setVersion(latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1);
        if (latest != null) {
            draft.setDisplayName(latest.getDisplayName());
            draft.setDescription(latest.getDescription());
            draft.setToolCodesJson(latest.getToolCodesJson());
            draft.setSopRules(latest.getSopRules());
            draft.setWhenToUse(latest.getWhenToUse());
            draft.setWhenNotToUse(latest.getWhenNotToUse());
            draft.setFieldPolicyJson(latest.getFieldPolicyJson());
            draft.setExamplesJson(latest.getExamplesJson());
        }
        return draft;
    }

    private void applyRequest(AgentSkillBundle bundle, UpdateAgentSkillBundleRequest request) {
        bundle.setDisplayName(trimToDefault(request.displayName(), bundle.getDisplayName()));
        bundle.setDescription(nullToEmpty(request.description()));
        bundle.setToolCodesJson(writeJson(request.toolCodes() == null ? List.of() : request.toolCodes()));
        bundle.setSopRules(nullToEmpty(request.sopRules()));
        bundle.setWhenToUse(nullToEmpty(request.whenToUse()));
        bundle.setWhenNotToUse(nullToEmpty(request.whenNotToUse()));
        bundle.setFieldPolicyJson(writeJson(request.fieldPolicy()));
        bundle.setExamplesJson(writeJson(request.examples()));
    }

    private AgentSkillBundleResponse toResponse(AgentSkillBundle bundle) {
        return new AgentSkillBundleResponse(
                bundle.getId(),
                bundle.getSkillCode(),
                nullToEmpty(bundle.getDisplayName()),
                nullToEmpty(bundle.getDescription()),
                readStringList(bundle.getToolCodesJson()),
                nullToEmpty(bundle.getSopRules()),
                nullToEmpty(bundle.getWhenToUse()),
                nullToEmpty(bundle.getWhenNotToUse()),
                readJson(bundle.getFieldPolicyJson()),
                readJson(bundle.getExamplesJson()),
                bundle.getStatus(),
                bundle.getVersion(),
                bundle.getPublishedAt() == null ? null : bundle.getPublishedAt().toString(),
                bundle.getUpdatedAt() == null ? null : bundle.getUpdatedAt().toString()
        );
    }

    private List<String> readStringList(String json) {
        Object value = readJson(json);
        if (value instanceof List<?> list) {
            return list.stream().map(Objects::toString).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return json;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Skill JSON 字段格式非法");
        }
    }

    private boolean matchesAny(String toolCode, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String normalized = toolCode.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String token = (pattern == null ? "" : pattern).trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (normalized.equals(token) || normalized.contains(token) || token.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String trimToDefault(String value, String defaultValue) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? nullToEmpty(defaultValue) : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
