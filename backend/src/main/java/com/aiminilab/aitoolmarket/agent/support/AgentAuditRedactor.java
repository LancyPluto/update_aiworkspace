package com.aiminilab.aitoolmarket.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AgentAuditRedactor {
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(authorization|api.?key|token|password|secret|cookie|credential).*");
    private static final Pattern EMAIL = Pattern.compile("(?i)([a-z0-9._%+-])[a-z0-9._%+-]*(@[a-z0-9.-]+\\.[a-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}([0-9Xx]{4})(?!\\d)");

    public JsonNode redact(JsonNode source) {
        JsonNode copy = source == null ? null : source.deepCopy();
        redactNode(copy);
        return copy;
    }

    public String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash audit payload", exception);
        }
    }

    private void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEY.matcher(field.getKey()).matches()) {
                    object.put(field.getKey(), "[REDACTED]");
                } else if (field.getValue().isTextual()) {
                    object.put(field.getKey(), redactText(field.getValue().asText()));
                } else {
                    redactNode(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isTextual()) array.set(i, array.textNode(redactText(value.asText())));
                else redactNode(value);
            }
        }
    }

    private String redactText(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:")) return "[DATA_URL sha256=" + sha256(value) + "]";
        String sanitized = value.replaceAll("(?i)([?&](signature|x-oss-signature|x-oss-credential|token|key)=)[^&#\\s]+", "$1[REDACTED]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("$1***$2");
        sanitized = PHONE.matcher(sanitized).replaceAll("$1****$2");
        return ID_CARD.matcher(sanitized).replaceAll("$1********$2");
    }
}
