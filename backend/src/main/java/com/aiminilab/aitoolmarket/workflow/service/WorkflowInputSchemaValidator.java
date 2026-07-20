package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowInputSchemaValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "number", "integer", "boolean", "array", "object"
    );

    private final ObjectMapper objectMapper;

    public WorkflowInputSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(ToolWorkflowVersion version, JsonNode input) {
        JsonNode schema = parsePublishedSchema(version);
        validateSchema(schema, "$inputSchema");
        JsonNode normalizedInput = input == null || input.isNull()
                ? objectMapper.createObjectNode()
                : input;
        List<String> errors = new ArrayList<>();
        validateValue("$", normalizedInput, schema, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Workflow input validation failed: " + String.join("; ", errors),
                    Map.of("fieldErrors", List.copyOf(errors))
            );
        }
    }

    private JsonNode parsePublishedSchema(ToolWorkflowVersion version) {
        if (version == null
                || version.getInputSchemaSnapshotJson() == null
                || version.getInputSchemaSnapshotJson().isBlank()) {
            throw invalidPublishedSchema();
        }
        try {
            JsonNode schema = objectMapper.readTree(version.getInputSchemaSnapshotJson());
            if (schema == null || !schema.isObject()) {
                throw invalidPublishedSchema();
            }
            return schema;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidPublishedSchema();
        }
    }

    private void validateSchema(JsonNode schema, String path) {
        if (!schema.isObject() || !schema.hasNonNull("type")) {
            throw invalidPublishedSchema();
        }
        String type = schema.path("type").asText();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw invalidPublishedSchema();
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && !enumNode.isArray()) {
            throw invalidPublishedSchema();
        }
        if ("object".equals(type)) {
            JsonNode properties = schema.get("properties");
            if (properties != null && !properties.isObject()) {
                throw invalidPublishedSchema();
            }
            JsonNode required = schema.get("required");
            if (required != null && !required.isArray()) {
                throw invalidPublishedSchema();
            }
            if (required != null) {
                required.forEach(item -> {
                    if (!item.isTextual()) {
                        throw invalidPublishedSchema();
                    }
                });
            }
            if (properties != null) {
                properties.fields().forEachRemaining(
                        entry -> validateSchema(entry.getValue(), path + "." + entry.getKey()));
            }
        }
        if ("array".equals(type) && schema.has("items")) {
            validateSchema(schema.get("items"), path + "[]");
        }
    }

    private void validateValue(String path, JsonNode value, JsonNode schema, List<String> errors) {
        String type = schema.path("type").asText();
        if (!matchesType(value, type)) {
            errors.add(path + " must be " + type);
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && !containsValue(enumNode, value)) {
            errors.add(path + " is not an allowed value");
            return;
        }
        if ("object".equals(type)) {
            JsonNode properties = schema.path("properties");
            Set<String> required = new HashSet<>();
            JsonNode requiredNode = schema.path("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(item -> required.add(item.asText()));
            }
            for (String field : required) {
                if (!value.has(field) || value.get(field).isNull()) {
                    errors.add(path + "." + field + " is required");
                }
            }
            properties.fields().forEachRemaining(entry -> {
                if (value.has(entry.getKey())) {
                    validateValue(path + "." + entry.getKey(), value.get(entry.getKey()), entry.getValue(), errors);
                }
            });
        } else if ("array".equals(type) && schema.has("items")) {
            for (int index = 0; index < value.size(); index++) {
                validateValue(path + "[" + index + "]", value.get(index), schema.get("items"), errors);
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    private boolean containsValue(JsonNode enumNode, JsonNode value) {
        for (JsonNode allowed : enumNode) {
            if (allowed.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private BusinessException invalidPublishedSchema() {
        return new BusinessException(
                ErrorCode.WORKFLOW_RUNTIME_BLOCKED,
                "Published workflow input schema is missing or invalid",
                Map.of("reason", "published_input_schema_invalid")
        );
    }
}
