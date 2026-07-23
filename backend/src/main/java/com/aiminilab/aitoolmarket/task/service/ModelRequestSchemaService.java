package com.aiminilab.aitoolmarket.task.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolFieldItem;
import com.aiminilab.aitoolmarket.tool.mapper.ToolFieldItemMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ModelRequestSchemaService {

    private final ToolFieldItemMapper toolFieldItemMapper;
    private final ObjectMapper objectMapper;

    public ModelRequestSchemaService(ToolFieldItemMapper toolFieldItemMapper, ObjectMapper objectMapper) {
        this.toolFieldItemMapper = toolFieldItemMapper;
        this.objectMapper = objectMapper;
    }

    public JsonNode validateAndSanitize(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        JsonNode source = params == null ? objectMapper.createObjectNode() : params;
        if (!isReady(modelConfig)) {
            return source;
        }
        if (!source.isObject()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "params must be a JSON object");
        }
        JsonNode schema = parseSchema(modelConfig.getRequestSchemaJson());
        JsonNode fieldsNode = schema.get("fields");
        if (fieldsNode == null || !fieldsNode.isArray()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model request schema fields must be an array");
        }

        ObjectNode output = ((ObjectNode) source).deepCopy();
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (JsonNode field : fieldsNode) {
            if (field == null || !field.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "model request schema fields items must be objects");
            }
            String key = fieldKey(field);
            if (key == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "model request schema field key is required");
            }
            if (fields.putIfAbsent(key, field) != null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "duplicate model request schema field: " + key);
            }
            JsonNode defaultValue = first(field.get("default"), field.get("defaultValue"));
            if (!output.has(key) && defaultValue != null && !defaultValue.isNull()) {
                output.set(key, defaultValue.deepCopy());
            }
        }

        Set<String> rootRequired = stringSet(schema.get("required"));
        Set<String> visibleKeys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
            String key = entry.getKey();
            JsonNode field = entry.getValue();
            if (!isVisible(field.get("visibleWhen"), output)) {
                output.remove(key);
                continue;
            }
            visibleKeys.add(key);
            JsonNode value = output.get(key);
            JsonNode requiredWhen = constraint(field, "requiredWhen");
            boolean required = field.path("required").asBoolean(false)
                    || rootRequired.contains(key)
                    || requiredWhen != null && isVisible(requiredWhen, output);
            if (required && isEmpty(value)) {
                throw invalid(key, "is required");
            }
            if (!isEmpty(value)) {
                validateValue(key, field, value);
            }
        }
        validateCrossFieldRequirements(fields, visibleKeys, output);
        validateRequiredAnyGroups(schema.get("requiresAnyGroups"), fields.keySet(), visibleKeys, output);

        Set<String> allowed = new HashSet<>(visibleKeys);
        if (tool != null && tool.getId() != null) {
            List<ToolFieldItem> toolFields = toolFieldItemMapper.findActiveFields(tool.getId());
            if (toolFields != null) {
                toolFields.stream()
                        .map(ToolFieldItem::getFieldKey)
                        .filter(key -> key != null && !key.isBlank())
                        .forEach(allowed::add);
            }
        }
        Iterator<String> names = output.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!allowed.contains(key) || fields.containsKey(key) && !visibleKeys.contains(key)) {
                names.remove();
            }
        }
        return output;
    }

    private void validateRequiredAnyGroups(JsonNode groups,
                                           Set<String> fieldKeys,
                                           Set<String> visibleKeys,
                                           ObjectNode params) {
        if (groups == null || groups.isNull()) {
            return;
        }
        if (!groups.isArray()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model request schema requiresAnyGroups must be an array");
        }
        for (JsonNode group : groups) {
            if (group == null || !group.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "model request schema requiresAnyGroups items must be objects");
            }
            JsonNode when = group.get("when");
            if (when != null && !isVisible(when, params)) {
                continue;
            }
            Set<String> candidates = stringSet(group.get("fields"));
            if (candidates.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "model request schema requiresAnyGroups.fields must contain field keys");
            }
            if (!fieldKeys.containsAll(candidates)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "model request schema requiresAnyGroups references unknown fields");
            }
            boolean matched = candidates.stream()
                    .anyMatch(key -> visibleKeys.contains(key) && !isEmpty(params.get(key)));
            if (!matched) {
                throw invalid(String.join("/", candidates), "requires at least one field");
            }
        }
    }

    private void validateCrossFieldRequirements(Map<String, JsonNode> fields,
                                                Set<String> visibleKeys,
                                                ObjectNode params) {
        for (String key : visibleKeys) {
            JsonNode value = params.get(key);
            if (isEmpty(value)) {
                continue;
            }
            JsonNode requiresAny = constraint(fields.get(key), "requiresAny");
            if (requiresAny == null || !requiresAny.isArray() || requiresAny.isEmpty()) {
                continue;
            }
            boolean matched = false;
            for (JsonNode candidate : requiresAny) {
                if (candidate.isTextual()
                        && visibleKeys.contains(candidate.asText())
                        && !isEmpty(params.get(candidate.asText()))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw invalid(key, "requires at least one related field");
            }
        }
    }

    private void validateValue(String key, JsonNode field, JsonNode value) {
        String type = text(field.get("type"));
        if (type != null && !matchesType(type, value)) {
            throw invalid(key, "must be of type " + type);
        }
        JsonNode allowedValues = first(constraint(field, "enum"), constraint(field, "values"), constraint(field, "options"));
        if (allowedValues != null && allowedValues.isArray() && !matchesEnum(value, allowedValues)) {
            throw invalid(key, "contains an unsupported value");
        }
        validateNumericBound(key, value, constraint(field, "min"), constraint(field, "minimum"), true);
        validateNumericBound(key, value, constraint(field, "max"), constraint(field, "maximum"), false);
        validateNumericStep(key, field, value);
        validateStringLength(key, field, value);
        if (value.isArray()) {
            JsonNode minItems = constraint(field, "minItems");
            JsonNode maxItems = constraint(field, "maxItems");
            if (minItems != null && minItems.canConvertToInt() && value.size() < minItems.asInt()) {
                throw invalid(key, "must contain at least " + minItems.asInt() + " items");
            }
            if (maxItems != null && maxItems.canConvertToInt() && value.size() > maxItems.asInt()) {
                throw invalid(key, "must contain at most " + maxItems.asInt() + " items");
            }
        }
    }

    private void validateNumericStep(String key, JsonNode field, JsonNode value) {
        JsonNode stepNode = constraint(field, "step");
        if (!value.isNumber() || stepNode == null || !stepNode.isNumber()) {
            return;
        }
        BigDecimal step = stepNode.decimalValue();
        if (step.signum() <= 0) {
            return;
        }
        JsonNode minimumNode = first(constraint(field, "min"), constraint(field, "minimum"));
        BigDecimal origin = minimumNode != null && minimumNode.isNumber()
                ? minimumNode.decimalValue()
                : BigDecimal.ZERO;
        if (value.decimalValue().subtract(origin).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw invalid(key, "does not match step " + step.stripTrailingZeros().toPlainString());
        }
    }

    private void validateStringLength(String key, JsonNode field, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }
        int length = value.asText().codePointCount(0, value.asText().length());
        JsonNode minLength = constraint(field, "minLength");
        JsonNode maxLength = constraint(field, "maxLength");
        if (minLength != null && minLength.canConvertToInt() && length < minLength.asInt()) {
            throw invalid(key, "must contain at least " + minLength.asInt() + " characters");
        }
        if (maxLength != null && maxLength.canConvertToInt() && length > maxLength.asInt()) {
            throw invalid(key, "must contain at most " + maxLength.asInt() + " characters");
        }
    }

    private void validateNumericBound(String key,
                                      JsonNode value,
                                      JsonNode primary,
                                      JsonNode fallback,
                                      boolean minimum) {
        JsonNode bound = first(primary, fallback);
        if (bound == null || !bound.isNumber() || !value.isNumber()) {
            return;
        }
        BigDecimal actual = value.decimalValue();
        BigDecimal expected = bound.decimalValue();
        if (minimum ? actual.compareTo(expected) < 0 : actual.compareTo(expected) > 0) {
            throw invalid(key, minimum ? "is below minimum" : "is above maximum");
        }
    }

    private boolean matchesType(String rawType, JsonNode value) {
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "string", "text", "textarea", "select", "url", "image", "video", "audio", "file" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer", "int" -> value.isIntegralNumber();
            case "boolean", "bool" -> value.isBoolean();
            case "array", "list", "images", "videos", "audios", "files" -> value.isArray();
            case "object", "json" -> value.isObject();
            default -> true;
        };
    }

    private boolean matchesEnum(JsonNode value, JsonNode allowedValues) {
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (!matchesEnum(item, allowedValues)) {
                    return false;
                }
            }
            return true;
        }
        for (JsonNode option : allowedValues) {
            JsonNode candidate = option != null && option.isObject() ? option.get("value") : option;
            if (candidate != null && (candidate.equals(value) || candidate.asText().equals(value.asText()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isVisible(JsonNode condition, ObjectNode params) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (condition.isArray()) {
            for (JsonNode item : condition) {
                if (!isVisible(item, params)) {
                    return false;
                }
            }
            return true;
        }
        if (!condition.isObject()) {
            return condition.asBoolean(true);
        }
        JsonNode anyOf = condition.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            boolean matched = false;
            for (JsonNode item : anyOf) {
                if (isVisible(item, params)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        JsonNode allOf = condition.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode item : allOf) {
                if (!isVisible(item, params)) {
                    return false;
                }
            }
        }
        JsonNode not = condition.get("not");
        if (not != null && isVisible(not, params)) {
            return false;
        }
        String dependentKey = text(first(condition.get("field"), condition.get("fieldKey"), condition.get("key")));
        if (dependentKey != null) {
            String operator = text(condition.get("operator"));
            JsonNode expected = first(condition.get("value"), condition.get("values"));
            if (expected == null) {
                if (condition.has("equals")) {
                    expected = condition.get("equals");
                    operator = "equals";
                } else if (condition.has("in")) {
                    expected = condition.get("in");
                    operator = "in";
                } else if (condition.has("notEquals")) {
                    expected = condition.get("notEquals");
                    operator = "notEquals";
                } else if (condition.has("notIn")) {
                    expected = condition.get("notIn");
                    operator = "notIn";
                }
            }
            return compare(params.get(dependentKey), operator, expected);
        }
        Iterator<Map.Entry<String, JsonNode>> entries = condition.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if ("anyOf".equals(entry.getKey()) || "allOf".equals(entry.getKey()) || "not".equals(entry.getKey())) {
                continue;
            }
            if (!compare(params.get(entry.getKey()), "equals", entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean compare(JsonNode actual, String rawOperator, JsonNode expected) {
        String operator = rawOperator == null ? "equals" : rawOperator.trim().toLowerCase(Locale.ROOT);
        if ("exists".equals(operator)) {
            return actual != null && !actual.isNull();
        }
        if ("truthy".equals(operator)) {
            return actual != null && !actual.isNull() && actual.asBoolean(!actual.asText().isBlank());
        }
        boolean matches;
        if (expected != null && expected.isArray()) {
            matches = false;
            for (JsonNode option : expected) {
                if (sameValue(actual, option)) {
                    matches = true;
                    break;
                }
            }
        } else {
            matches = sameValue(actual, expected);
        }
        return switch (operator) {
            case "notequals", "not_equals", "neq", "notin", "not_in" -> !matches;
            default -> matches;
        };
    }

    private boolean sameValue(JsonNode left, JsonNode right) {
        return left != null && right != null && (left.equals(right) || left.asText().equals(right.asText()));
    }

    private JsonNode constraint(JsonNode field, String name) {
        JsonNode direct = field.get(name);
        if (direct != null) {
            return direct;
        }
        JsonNode constraints = field.get("constraints");
        return constraints != null && constraints.isObject() ? constraints.get(name) : null;
    }

    private Set<String> stringSet(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().trim());
            }
        });
        return values;
    }

    private String fieldKey(JsonNode field) {
        return text(first(field.get("key"), field.get("fieldKey"), field.get("name")));
    }

    private JsonNode parseSchema(String raw) {
        try {
            JsonNode schema = objectMapper.readTree(raw);
            if (schema == null || !schema.isObject()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "model request schema must be a JSON object");
            }
            return schema;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model request schema is invalid JSON");
        }
    }

    private boolean isReady(AgentModelConfig config) {
        return config != null
                && "READY".equalsIgnoreCase(config.getContractStatus())
                && config.getRequestSchemaJson() != null
                && !config.getRequestSchemaJson().isBlank();
    }

    private boolean isEmpty(JsonNode value) {
        return value == null || value.isNull()
                || value.isTextual() && value.asText().isBlank()
                || value.isArray() && value.isEmpty();
    }

    private String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    private JsonNode first(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private BusinessException invalid(String key, String reason) {
        return new BusinessException(ErrorCode.PARAM_ERROR, "invalid model parameter " + key + ": " + reason);
    }
}
