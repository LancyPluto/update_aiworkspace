package com.aiminilab.aitoolmarket.tool.support;

import com.aiminilab.aitoolmarket.ppt.PptConstants;
import com.aiminilab.aitoolmarket.tool.integration.ToolIntegrationConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 管理端更新工具时，避免用不含集成块的 config_note 覆盖 ppt-workflow / tool-integration。
 * 工作台类工具的模型与引擎密钥应通过专用 API（如 ppt-workflow）写入。
 */
public final class ConfigNoteMergeSupport {

    private ConfigNoteMergeSupport() {
    }

    /**
     * 配置包导入更新已有工具时，保留库中已配置的 ai-tool-ui 媒体 URL（图标、模型效果、对比图等）。
     */
    public static String mergePreservingMediaUrls(String existingConfigNote,
                                                  String incomingConfigNote,
                                                  ObjectMapper objectMapper) {
        if (incomingConfigNote == null) {
            return null;
        }
        if (existingConfigNote == null || existingConfigNote.isBlank()) {
            return incomingConfigNote;
        }
        ToolFrontendStyleConfig existingStyle = ToolFrontendStyleConfig.fromConfigNote(existingConfigNote, objectMapper);
        ToolFrontendStyleConfig incomingStyle = ToolFrontendStyleConfig.fromConfigNote(incomingConfigNote, objectMapper);
        ToolFrontendStyleConfig merged = incomingStyle.mergePreservingMediaUrls(existingStyle);
        return ToolFrontendStyleConfig.replaceOrAppendFrontendStyleMarker(incomingConfigNote, merged, objectMapper);
    }

    public static String preferNonBlankUrl(String existingUrl, String incomingUrl) {
        if (incomingUrl != null && !incomingUrl.isBlank()) {
            return incomingUrl;
        }
        if (existingUrl != null && !existingUrl.isBlank()) {
            return existingUrl;
        }
        return incomingUrl;
    }

    /**
     * 若请求体未携带集成块、但库中已有，则把库中的集成块追加回新 config_note。
     */
    public static String mergePreservingIntegrationMarkers(String existingConfigNote, String incomingConfigNote) {
        if (incomingConfigNote == null) {
            return null;
        }
        if (hasIntegrationMarker(incomingConfigNote)) {
            return incomingConfigNote;
        }
        List<String> preserved = extractIntegrationMarkers(existingConfigNote);
        if (preserved.isEmpty()) {
            return incomingConfigNote;
        }
        String base = stripIntegrationMarkers(incomingConfigNote).strip();
        StringBuilder merged = new StringBuilder(base);
        for (String marker : preserved) {
            if (merged.length() > 0) {
                merged.append("\n\n");
            }
            merged.append(marker);
        }
        return merged.toString();
    }

    public static boolean hasIntegrationMarker(String configNote) {
        if (configNote == null || configNote.isBlank()) {
            return false;
        }
        return PptConstants.WORKFLOW_PATTERN.matcher(configNote).find()
                || ToolIntegrationConstants.MARKER_PATTERN.matcher(configNote).find();
    }

    public static List<String> extractIntegrationMarkers(String configNote) {
        List<String> markers = new ArrayList<>();
        if (configNote == null || configNote.isBlank()) {
            return markers;
        }
        appendMatches(markers, PptConstants.WORKFLOW_PATTERN.matcher(configNote));
        appendMatches(markers, ToolIntegrationConstants.MARKER_PATTERN.matcher(configNote));
        return markers;
    }

    public static String stripIntegrationMarkers(String configNote) {
        if (configNote == null) {
            return "";
        }
        String stripped = PptConstants.WORKFLOW_PATTERN.matcher(configNote).replaceAll("");
        return ToolIntegrationConstants.MARKER_PATTERN.matcher(stripped).replaceAll("").trim();
    }

    private static void appendMatches(List<String> markers, Matcher matcher) {
        while (matcher.find()) {
            markers.add(matcher.group().trim());
        }
    }
}
