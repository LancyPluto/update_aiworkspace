package com.aiminilab.aitoolmarket.agent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ModelCapabilitiesCodec {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ModelCapabilitiesCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, LIST_TYPE);
            List<String> normalized = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim().toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(normalized);
        } catch (Exception exception) {
            return List.of();
        }
    }

    public String serialize(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "[]";
        }
        try {
            List<String> normalized = capabilities.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            return "[]";
        }
    }
}
