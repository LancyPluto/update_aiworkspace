package com.aiminilab.aitoolmarket.common.error;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ErrorDefinitionRegistry {

    private static final Map<String, ErrorDefinition> DEFINITIONS = buildDefinitions();

    private ErrorDefinitionRegistry() {
    }

    public static Optional<ErrorDefinition> find(String code) {
        return Optional.ofNullable(DEFINITIONS.get(code));
    }

    public static Collection<ErrorDefinition> all() {
        return DEFINITIONS.values();
    }

    public static void validate() {
        // Class initialization performs format and duplicate validation.
    }

    private static Map<String, ErrorDefinition> buildDefinitions() {
        Map<String, ErrorDefinition> definitions = new LinkedHashMap<>();
        register(definitions, ApiErrors.values());
        register(definitions, AuthErrors.values());
        register(definitions, ToolErrors.values());
        register(definitions, TaskErrors.values());
        register(definitions, ModelErrors.values());
        register(definitions, PayErrors.values());
        register(definitions, SystemErrors.values());
        register(definitions, LegacyErrorDefinitions.values());
        return Collections.unmodifiableMap(definitions);
    }

    private static void register(Map<String, ErrorDefinition> definitions, ErrorDefinition[] candidates) {
        for (ErrorDefinition candidate : candidates) {
            ErrorDefinition previous = definitions.putIfAbsent(candidate.code(), candidate);
            if (previous != null) {
                throw new IllegalStateException("Duplicate application error code: " + candidate.code());
            }
        }
    }
}
