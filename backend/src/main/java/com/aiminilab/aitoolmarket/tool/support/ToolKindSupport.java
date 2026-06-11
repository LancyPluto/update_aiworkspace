package com.aiminilab.aitoolmarket.tool.support;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ToolKindSupport {

    private ToolKindSupport() {
    }

    public static String resolve(AiTool tool) {
        if (tool == null) {
            return "other";
        }
        return resolve(
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryName(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                tool.getExecutionHandler()
        );
    }

    public static String resolve(String toolCode,
                                 String toolName,
                                 String categoryName,
                                 String toolType,
                                 String inputModality,
                                 String outputModality,
                                 String executionHandler) {
        String type = upper(toolType);
        String input = upper(inputModality);
        String output = upper(outputModality);
        String handler = upper(executionHandler);
        String searchable = Stream.of(toolCode, toolName, categoryName, toolType, executionHandler)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));

        if ("AGENT".equals(type)) {
            return "agent";
        }
        if (handler.contains("DIGITAL_HUMAN")
                || type.contains("DIGITAL_HUMAN")
                || searchable.contains("digital_human")
                || searchable.contains("digital-human")
                || searchable.contains("数字人")
                || searchable.contains("口播")) {
            return "digitalHuman";
        }
        if ("AUDIO".equals(output)
                || "AUDIO".equals(input)
                || type.contains("AUDIO")
                || type.contains("SPEECH")
                || handler.contains("AUDIO")) {
            return "audio";
        }
        if ("VIDEO".equals(output) || type.contains("VIDEO") || handler.contains("VIDEO")) {
            return "video";
        }
        if ("IMAGE".equals(output) || "IMAGE".equals(input) || type.contains("IMAGE") || handler.contains("IMAGE")) {
            return "image";
        }
        if ("TEXT".equals(output) || "TEXT".equals(input) || type.contains("TEXT")) {
            return "text";
        }
        return "other";
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
