package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelOptionGroupResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelOptionItemResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PublicModelOptionService {

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final VendorCodeResolver vendorCodeResolver;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final ImageGenerationParameterResolver imageParameterResolver;

    public PublicModelOptionService(AgentModelConfigMapper agentModelConfigMapper,
                                    ModelVendorAccountMapper vendorAccountMapper,
                                    VendorCodeResolver vendorCodeResolver,
                                    ModelCapabilitiesCodec capabilitiesCodec,
                                    ImageGenerationParameterResolver imageParameterResolver) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorAccountMapper = vendorAccountMapper;
        this.vendorCodeResolver = vendorCodeResolver;
        this.capabilitiesCodec = capabilitiesCodec;
        this.imageParameterResolver = imageParameterResolver;
    }

    public List<ModelOptionGroupResponse> list(String mode) {
        String requiredCapability = capabilityForMode(mode);
        List<ModelVendorAccount> accounts = vendorAccountMapper.findAllActive();
        Map<Long, ModelVendorAccount> accountById = accounts.stream()
                .collect(Collectors.toMap(ModelVendorAccount::getId, account -> account, (a, b) -> a));
        Map<String, List<ModelOptionItemResponse>> grouped = new LinkedHashMap<>();

        for (AgentModelConfig config : agentModelConfigMapper.findAgentEnabled()) {
            if (!supports(config, requiredCapability)) {
                continue;
            }
            String vendorCode = resolveVendorCode(config, accountById);
            String vendorName = vendorCodeResolver.vendorLabel(vendorCode);
            grouped.computeIfAbsent(vendorCode, key -> new ArrayList<>())
                    .add(ModelOptionItemResponse.from(config, vendorCode, vendorName, capabilitiesCodec, imageParameterResolver));
        }

        return grouped.entrySet().stream()
                .sorted(Comparator.comparing(entry -> vendorCodeResolver.vendorLabel(entry.getKey())))
                .map(entry -> new ModelOptionGroupResponse(
                        entry.getKey(),
                        vendorCodeResolver.vendorLabel(entry.getKey()),
                        "/assets/vendor-icons/" + vendorCodeResolver.vendorIconAsset(entry.getKey()) + ".svg",
                        sortModels(entry.getValue())
                ))
                .filter(group -> !group.models().isEmpty())
                .toList();
    }

    private boolean supports(AgentModelConfig config, String requiredCapability) {
        return capabilitiesCodec.parse(config.getCapabilities()).stream()
                .anyMatch(capability -> capability.equalsIgnoreCase(requiredCapability));
    }

    private String resolveVendorCode(AgentModelConfig config, Map<Long, ModelVendorAccount> accountById) {
        if (config.getVendorAccountId() != null) {
            ModelVendorAccount account = accountById.get(config.getVendorAccountId());
            if (account != null && account.getVendorCode() != null && !account.getVendorCode().isBlank()) {
                return canonicalVendorCode(account.getVendorCode());
            }
        }
        return canonicalVendorCode(vendorCodeResolver.resolveVendorCode(config));
    }

    private static String canonicalVendorCode(String vendorCode) {
        if ("openai_gateway".equalsIgnoreCase(vendorCode)) {
            return "openai";
        }
        return vendorCode == null || vendorCode.isBlank()
                ? "other"
                : vendorCode.trim().toLowerCase(Locale.ROOT);
    }

    private static List<ModelOptionItemResponse> sortModels(List<ModelOptionItemResponse> models) {
        return models.stream()
                .sorted(Comparator
                        .comparing((ModelOptionItemResponse model) -> !Boolean.TRUE.equals(model.isDefault()))
                        .thenComparing(model -> text(model.displayName()))
                        .thenComparing(model -> text(model.modelName())))
                .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String capabilityForMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image", "picture", "photo" -> "IMAGE_GENERATION";
            case "video" -> "VIDEO_GENERATION";
            case "digitalhuman", "digital_human", "avatar" -> "DIGITAL_HUMAN";
            case "audio", "voice", "speech" -> "TEXT_TO_SPEECH";
            default -> "TEXT_GENERATION";
        };
    }
}
