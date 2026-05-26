package com.aiminilab.aitoolmarket.market.support;

import com.aiminilab.aitoolmarket.market.dto.CapabilityDto;
import com.aiminilab.aitoolmarket.market.entity.AiMarketTool;
import com.aiminilab.aitoolmarket.market.enums.CapabilityType;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FileReadingPolicy {

    private static final Set<String> DEFAULT_TYPES = Set.of("pdf", "txt", "png", "jpg", "jpeg");
    private static final int DEFAULT_MAX_MB = 20;

    private FileReadingPolicy() {
    }

    public static Set<String> defaultExtensions() {
        return DEFAULT_TYPES;
    }

    public static int defaultMaxMb() {
        return DEFAULT_MAX_MB;
    }

    public static Policy resolve(AiMarketTool tool, CapabilitiesCodec codec) {
        List<CapabilityDto> capabilities = codec.parse(tool.getCapabilitiesJson());
        for (CapabilityDto capability : capabilities) {
            if (!CapabilityType.fileReading.name().equals(capability.type())) {
                continue;
            }
            Map<String, Object> config = capability.config();
            if (config == null) {
                break;
            }
            Set<String> types = new HashSet<>();
            Object rawTypes = config.get("supportedFileTypes");
            if (rawTypes instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        types.add(item.toString().toLowerCase(Locale.ROOT));
                    }
                }
            }
            int maxMb = DEFAULT_MAX_MB;
            Object rawMax = config.get("maxSizeMB");
            if (rawMax instanceof Number number) {
                maxMb = number.intValue();
            }
            if (types.isEmpty()) {
                types.addAll(DEFAULT_TYPES);
            }
            return new Policy(types, maxMb);
        }
        return new Policy(DEFAULT_TYPES, DEFAULT_MAX_MB);
    }

    public record Policy(Set<String> supportedExtensions, int maxSizeMb) {
        public long maxSizeBytes() {
            return maxSizeMb * 1024L * 1024L;
        }
    }
}
