package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.dto.ImageGenerationParametersResponse;
import com.aiminilab.aitoolmarket.agent.dto.ImageSizeOptionResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ImageGenerationParameterResolver {

    private static final List<String> GPT_IMAGE_2_4K_SIZES = List.of(
            "2560x2560",
            "2880x2880",
            "3072x1728",
            "3840x2160",
            "1728x3072",
            "2160x3840",
            "2560x1920",
            "3072x2304",
            "1920x2560",
            "2304x3072",
            "2880x1920",
            "3072x2048",
            "3456x2304",
            "1920x2880",
            "2048x3072",
            "2304x3456",
            "3072x1536",
            "3840x1920",
            "1536x3072",
            "1920x3840",
            "3840x1280",
            "1280x3840"
    );

    private static final List<String> OPENAI_STANDARD_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");
    private static final List<String> GENERIC_IMAGE_SIZES = List.of(
            "1024x1024", "1280x720", "720x1280", "1024x768", "768x1024", "1152x768", "768x1152"
    );
    private static final Set<String> IMAGE_COUNT_FIELD_KEYS = Set.of(
            "count",
            "n",
            "batchsize",
            "imagecount",
            "imagescount",
            "numimages",
            "numberofimages",
            "outputcount"
    );

    private final ObjectMapper objectMapper;

    public ImageGenerationParameterResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ImageGenerationParametersResponse resolve(AgentModelConfig config) {
        if (config == null || !hasImageGeneration(config) || isKlingVideoOnlyModel(config)) {
            return null;
        }
        JsonNode extra = parseExtraAuthJson(config.getExtraAuthJson());
        List<ImageSizeOptionResponse> sizes = configuredSizes(extra);
        if (sizes.isEmpty()) {
            sizes = defaultSizes(config).stream().map(size -> new ImageSizeOptionResponse(size, size)).toList();
        }
        String requestedDefaultSize = configuredText(extra, "defaultImageSize", "defaultSize", "imageSize", "size");
        String defaultSize = requestedDefaultSize;
        if (requestedDefaultSize == null || sizes.stream().noneMatch(option -> option.value().equals(requestedDefaultSize))) {
            defaultSize = sizes.isEmpty() ? null : sizes.get(0).value();
        }
        CountConfiguration countConfiguration = configuredCounts(config.getRequestSchemaJson());
        List<Integer> counts = countConfiguration.counts();
        Integer defaultCount = countConfiguration.defaultCount();
        List<ImageSizeOptionResponse> qualities = configuredOptions(
                extra,
                "qualities",
                "allowedQualities",
                "imageQualities",
                "qualityOptions"
        );
        String requestedDefaultQuality = configuredText(extra, "defaultQuality", "imageQuality", "quality");
        String defaultQuality = requestedDefaultQuality;
        if (requestedDefaultQuality == null || qualities.stream().noneMatch(option -> option.value().equals(requestedDefaultQuality))) {
            defaultQuality = qualities.isEmpty() ? null : qualities.get(0).value();
        }
        return new ImageGenerationParametersResponse(sizes, defaultSize, counts, defaultCount, qualities, defaultQuality);
    }

    private static boolean hasImageGeneration(AgentModelConfig config) {
        return String.valueOf(config.getCapabilities()).toUpperCase(Locale.ROOT).contains("IMAGE_GENERATION");
    }

    private List<ImageSizeOptionResponse> configuredSizes(JsonNode extra) {
        return configuredOptions(extra, "imageSizes", "allowedSizes", "allowedImageSizes", "sizes");
    }

    private List<ImageSizeOptionResponse> configuredOptions(JsonNode extra, String... keys) {
        JsonNode optionsNode = first(extra, keys);
        if (optionsNode == null || !optionsNode.isArray()) {
            return List.of();
        }
        List<ImageSizeOptionResponse> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : optionsNode) {
            String label;
            String value;
            if (item.isTextual()) {
                value = item.asText().trim();
                label = value;
            } else {
                value = textValue(item.get("value"), item.get("size"));
                label = textValue(item.get("label"), item.get("name"), item.get("value"), item.get("size"));
            }
            if (value == null || value.isBlank() || !seen.add(value)) {
                continue;
            }
            options.add(new ImageSizeOptionResponse(cleanOptionLabel(label, value), value));
        }
        return options;
    }

    private static String cleanOptionLabel(String label, String value) {
        if (label == null || label.isBlank()) {
            return value;
        }
        String trimmed = label.trim();
        if (trimmed.contains("??") || trimmed.contains("\uFFFD")) {
            return value;
        }
        return trimmed;
    }

    private static List<String> defaultSizes(AgentModelConfig config) {
        String provider = normalized(config.getProvider());
        String modelName = normalized(config.getModelName());
        if ("openai_images_gateway".equals(provider) && "gpt-image-2-4k".equals(modelName)) {
            return GPT_IMAGE_2_4K_SIZES;
        }
        if ("openai_images_gateway".equals(provider) || "ofox_openai_images".equals(provider)) {
            return OPENAI_STANDARD_SIZES;
        }
        if ("agnes_images".equals(provider)) {
            return OPENAI_STANDARD_SIZES;
        }
        if ("volcengine_images".equals(provider)) {
            return OPENAI_STANDARD_SIZES;
        }
        if ("kling_video".equals(provider)) {
            return GENERIC_IMAGE_SIZES;
        }
        return GENERIC_IMAGE_SIZES;
    }

    private CountConfiguration configuredCounts(String requestSchemaJson) {
        JsonNode fields = parseJsonObject(requestSchemaJson).get("fields");
        if (fields == null || !fields.isArray()) {
            return CountConfiguration.singleImage();
        }
        for (JsonNode field : fields) {
            if (!field.isObject() || !isImageCountField(field)) {
                continue;
            }
            Set<Integer> counts = new LinkedHashSet<>();
            JsonNode options = field.get("enum");
            if (options != null && options.isArray()) {
                for (JsonNode option : options) {
                    JsonNode value = option.isObject() ? option.get("value") : option;
                    Integer count = positiveInt(value);
                    if (count != null && count <= 100) {
                        counts.add(count);
                    }
                }
            }
            Integer min = positiveInt(field.get("min"));
            Integer max = positiveInt(field.get("max"));
            if (counts.isEmpty() && max != null) {
                int lowerBound = min == null ? 1 : min;
                for (int count = lowerBound; count <= Math.min(max, 100); count++) {
                    counts.add(count);
                }
            }
            Integer configuredDefault = positiveInt(field.get("default"));
            if (counts.isEmpty() && configuredDefault != null) {
                counts.add(configuredDefault);
            }
            if (counts.isEmpty()) {
                counts.add(1);
            }
            List<Integer> resolved = new ArrayList<>(counts);
            Integer defaultCount = configuredDefault != null && counts.contains(configuredDefault)
                    ? configuredDefault
                    : resolved.get(0);
            return new CountConfiguration(resolved, defaultCount);
        }
        return CountConfiguration.singleImage();
    }

    private static boolean isImageCountField(JsonNode field) {
        String type = normalized(textValue(field.get("type")));
        if (!"integer".equals(type) && !"number".equals(type)) {
            return false;
        }
        String key = normalized(textValue(field.get("key"))).replaceAll("[^a-z0-9]", "");
        return IMAGE_COUNT_FIELD_KEYS.contains(key);
    }

    private static Integer positiveInt(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            int parsed = value.asInt();
            return parsed > 0 ? parsed : null;
        }
        return parsePositiveInt(value.asText(""));
    }

    private JsonNode parseExtraAuthJson(String value) {
        return parseJsonObject(value);
    }

    private JsonNode parseJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed == null || !parsed.isObject() ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static JsonNode first(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String configuredText(JsonNode extra, String... keys) {
        JsonNode value = first(extra, keys);
        return textValue(value);
    }

    private static Integer configuredInt(JsonNode extra, String... keys) {
        JsonNode value = first(extra, keys);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        return parsePositiveInt(value.asText(""));
    }

    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isKlingVideoOnlyModel(AgentModelConfig config) {
        if (!"kling_video".equals(normalized(config.getProvider()))) {
            return false;
        }
        String modelName = normalized(config.getModelName());
        String text = normalized(String.join(" ",
                String.valueOf(config.getDisplayName()),
                String.valueOf(config.getConfigCode()),
                String.valueOf(config.getModelName())
        ));
        boolean explicitImageModel = text.contains("可灵生图") || "kling-v2-1".equals(modelName) || text.contains("image_generation");
        boolean videoModel = text.contains("图生视频")
                || text.contains("image-to-video")
                || text.contains("i2v")
                || text.contains("motion")
                || text.contains("omni")
                || text.contains("video o1");
        return !explicitImageModel || videoModel;
    }

    private static String textValue(JsonNode... nodes) {
        if (nodes == null) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull()) {
                String text = node.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record CountConfiguration(List<Integer> counts, Integer defaultCount) {
        private static CountConfiguration singleImage() {
            return new CountConfiguration(List.of(1), 1);
        }
    }
}
