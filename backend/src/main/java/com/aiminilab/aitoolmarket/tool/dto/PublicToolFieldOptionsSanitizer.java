package com.aiminilab.aitoolmarket.tool.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

final class PublicToolFieldOptionsSanitizer {

    private static final Set<String> TEXT_KEYS = Set.of(
            "uiRole", "role", "placement", "uiTier", "uiGroup", "uiGroupLabel",
            "layoutHint", "helpText", "submitPolicy", "accept",
            "forceCharacterOrientation", "libraryKind", "unit"
    );
    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "core", "isCore", "requiresPublicUrl", "libraryEnabled"
    );
    private static final Set<String> NUMBER_KEYS = Set.of(
            "uiOrder", "maxLength", "minCount", "maxCount", "maxItems",
            "maxSizeMb", "minDuration"
    );
    private static final Set<String> STRING_ARRAY_KEYS = Set.of(
            "allowedModes", "accepts", "mimeTypes", "formats", "extensions"
    );
    private static final Set<String> NUMBER_MAP_KEYS = Set.of(
            "maxLengthByModel", "durationByOrientation"
    );

    private PublicToolFieldOptionsSanitizer() {
    }

    static JsonNode sanitize(JsonNode source) {
        if (source == null || source.isNull()) {
            return null;
        }
        if (source.isArray()) {
            return sanitizeOptionRows(source);
        }
        if (!source.isObject()) {
            return null;
        }

        ObjectNode safe = JsonNodeFactory.instance.objectNode();
        TEXT_KEYS.forEach(key -> copyText(source, safe, key));
        BOOLEAN_KEYS.forEach(key -> copyBoolean(source, safe, key));
        NUMBER_KEYS.forEach(key -> copyNumber(source, safe, key));
        STRING_ARRAY_KEYS.forEach(key -> copyTextOrTextArray(source, safe, key));
        NUMBER_MAP_KEYS.forEach(key -> copyNumberMap(source, safe, key));
        copyScalar(source, safe, "defaultValue");
        copySlider(source, safe);
        copyVisibleWhen(source, safe);

        JsonNode optionRows = source.get("options");
        if (optionRows != null && optionRows.isArray()) {
            safe.set("options", sanitizeOptionRows(optionRows));
        }
        return safe.isEmpty() ? null : safe;
    }

    private static ArrayNode sanitizeOptionRows(JsonNode source) {
        ArrayNode safe = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : source) {
            if (item.isTextual() || item.isNumber() || item.isBoolean()) {
                safe.add(item.asText());
                continue;
            }
            if (!item.isObject()) {
                continue;
            }
            String label = firstText(item, "label", "name", "value");
            String value = firstText(item, "value", "label", "name");
            if (label == null && value == null) {
                continue;
            }
            ObjectNode option = JsonNodeFactory.instance.objectNode();
            option.put("label", label == null ? value : label);
            option.put("value", value == null ? label : value);
            safe.add(option);
        }
        return safe;
    }

    private static String firstText(JsonNode source, String... keys) {
        for (String key : keys) {
            JsonNode value = source.get(key);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static void copyText(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value != null && value.isTextual()) {
            target.put(key, value.textValue());
        }
    }

    private static void copyBoolean(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value != null && value.isBoolean()) {
            target.put(key, value.booleanValue());
        }
    }

    private static void copyNumber(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value != null && value.isNumber()) {
            target.set(key, value.deepCopy());
        }
    }

    private static void copyScalar(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value != null && (value.isTextual() || value.isNumber() || value.isBoolean())) {
            target.set(key, value.deepCopy());
        }
    }

    private static void copyTextOrTextArray(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            target.put(key, value.textValue());
            return;
        }
        if (!value.isArray()) {
            return;
        }
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : value) {
            if (item.isValueNode() && !item.isNull()) {
                values.add(item.asText());
            }
        }
        target.set(key, values);
    }

    private static void copyNumberMap(JsonNode source, ObjectNode target, String key) {
        JsonNode value = source.get(key);
        if (value == null || !value.isObject()) {
            return;
        }
        ObjectNode values = JsonNodeFactory.instance.objectNode();
        value.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                values.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        if (!values.isEmpty()) {
            target.set(key, values);
        }
    }

    private static void copySlider(JsonNode source, ObjectNode target) {
        JsonNode value = source.get("slider");
        if (value == null || !value.isObject()) {
            return;
        }
        ObjectNode slider = JsonNodeFactory.instance.objectNode();
        copyNumber(value, slider, "min");
        copyNumber(value, slider, "max");
        copyNumber(value, slider, "step");
        if (!slider.isEmpty()) {
            target.set("slider", slider);
        }
    }

    private static void copyVisibleWhen(JsonNode source, ObjectNode target) {
        JsonNode value = source.get("visibleWhen");
        if (value == null || !value.isObject()) {
            return;
        }
        ObjectNode conditions = JsonNodeFactory.instance.objectNode();
        value.fields().forEachRemaining(entry -> {
            JsonNode condition = entry.getValue();
            if (condition.isValueNode() && !condition.isNull()) {
                conditions.put(entry.getKey(), condition.asText());
                return;
            }
            if (!condition.isArray()) {
                return;
            }
            ArrayNode allowed = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : condition) {
                if (item.isValueNode() && !item.isNull()) {
                    allowed.add(item.asText());
                }
            }
            conditions.set(entry.getKey(), allowed);
        });
        if (!conditions.isEmpty()) {
            target.set("visibleWhen", conditions);
        }
    }
}
