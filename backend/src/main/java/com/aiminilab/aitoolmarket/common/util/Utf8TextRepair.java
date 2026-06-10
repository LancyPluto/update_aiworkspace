package com.aiminilab.aitoolmarket.common.util;

import java.nio.charset.StandardCharsets;

/**
 * Repairs UTF-8 text that was incorrectly decoded as ISO-8859-1 (common JDBC mojibake).
 */
public final class Utf8TextRepair {

    private Utf8TextRepair() {
    }

    public static String repairIfNeeded(String text) {
        if (text == null || text.isBlank() || !looksLikeMojibake(text)) {
            return text;
        }
        try {
            String repaired = new String(text.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            if (containsCjk(repaired) && !containsReplacementChar(repaired)) {
                return repaired;
            }
        } catch (Exception ignored) {
            // keep original
        }
        return text;
    }

    private static boolean looksLikeMojibake(String text) {
        return text.indexOf('ä') >= 0
                || text.indexOf('å') >= 0
                || text.indexOf('è') >= 0
                || text.indexOf('æ') >= 0
                || text.indexOf('Ã') >= 0
                || text.indexOf("u610f") >= 0;
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private static boolean containsReplacementChar(String text) {
        return text.indexOf('\uFFFD') >= 0;
    }
}
