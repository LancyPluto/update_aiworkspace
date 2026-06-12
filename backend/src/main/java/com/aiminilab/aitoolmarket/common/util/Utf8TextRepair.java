package com.aiminilab.aitoolmarket.common.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Repairs UTF-8 text that was incorrectly decoded as ISO-8859-1 (common JDBC mojibake),
 * including cases where Latin-1 bytes were stored again as UTF-8 with Windows-1252 expansions.
 */
public final class Utf8TextRepair {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static final Map<Character, Byte> WINDOWS_1252_OVERRIDES = Map.ofEntries(
            Map.entry('\u20AC', (byte) 0x80),
            Map.entry('\u201A', (byte) 0x82),
            Map.entry('\u0192', (byte) 0x83),
            Map.entry('\u201E', (byte) 0x84),
            Map.entry('\u2026', (byte) 0x85),
            Map.entry('\u2020', (byte) 0x86),
            Map.entry('\u2021', (byte) 0x87),
            Map.entry('\u02C6', (byte) 0x88),
            Map.entry('\u2030', (byte) 0x89),
            Map.entry('\u0160', (byte) 0x8A),
            Map.entry('\u2039', (byte) 0x8B),
            Map.entry('\u0152', (byte) 0x8C),
            Map.entry('\u017D', (byte) 0x8E),
            Map.entry('\u2018', (byte) 0x91),
            Map.entry('\u2019', (byte) 0x92),
            Map.entry('\u201C', (byte) 0x93),
            Map.entry('\u201D', (byte) 0x94),
            Map.entry('\u2022', (byte) 0x95),
            Map.entry('\u2013', (byte) 0x96),
            Map.entry('\u2014', (byte) 0x97),
            Map.entry('\u02DC', (byte) 0x98),
            Map.entry('\u2122', (byte) 0x99),
            Map.entry('\u0161', (byte) 0x9A),
            Map.entry('\u203A', (byte) 0x9B),
            Map.entry('\u0153', (byte) 0x9C),
            Map.entry('\u017E', (byte) 0x9E),
            Map.entry('\u0178', (byte) 0x9F)
    );

    private Utf8TextRepair() {
    }

    public static String repairIfNeeded(String text) {
        if (text == null || text.isBlank() || !looksLikeMojibake(text)) {
            return text;
        }
        String repaired = tryDecodeFromBytes(text, StandardCharsets.ISO_8859_1);
        if (repaired != null) {
            return repaired;
        }
        repaired = tryDecodeFromBytes(text, WINDOWS_1252);
        if (repaired != null) {
            return repaired;
        }
        repaired = tryDecodeFromManualMapping(text);
        if (repaired != null) {
            return repaired;
        }
        return text;
    }

    private static String tryDecodeFromBytes(String text, Charset charset) {
        try {
            byte[] bytes = text.getBytes(charset);
            String repaired = new String(bytes, StandardCharsets.UTF_8);
            if (containsCjk(repaired) && !containsReplacementChar(repaired)) {
                return repaired;
            }
        } catch (Exception ignored) {
            // keep trying other strategies
        }
        return null;
    }

    private static String tryDecodeFromManualMapping(String text) {
        try {
            byte[] bytes = new byte[text.length()];
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch <= 0xFF) {
                    bytes[i] = (byte) ch;
                    continue;
                }
                Byte mapped = WINDOWS_1252_OVERRIDES.get(ch);
                if (mapped == null) {
                    return null;
                }
                bytes[i] = mapped;
            }
            String repaired = new String(bytes, StandardCharsets.UTF_8);
            if (containsCjk(repaired) && !containsReplacementChar(repaired)) {
                return repaired;
            }
        } catch (Exception ignored) {
            // keep original
        }
        return null;
    }

    private static boolean looksLikeMojibake(String text) {
        return text.indexOf('ä') >= 0
                || text.indexOf('å') >= 0
                || text.indexOf('è') >= 0
                || text.indexOf('æ') >= 0
                || text.indexOf('Ã') >= 0
                || text.indexOf('\u201E') >= 0
                || text.indexOf('\u0153') >= 0
                || text.indexOf("u610f") >= 0;
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private static boolean containsReplacementChar(String text) {
        return text.indexOf('\uFFFD') >= 0;
    }
}
