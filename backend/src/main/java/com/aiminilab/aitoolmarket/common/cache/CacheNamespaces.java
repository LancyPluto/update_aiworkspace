package com.aiminilab.aitoolmarket.common.cache;

public final class CacheNamespaces {

    public static final String PREFIX = "cache:";
    private static final String PUBLIC_TOOL_CONTRACT_VERSION = "v4";

    public static final String TOOL_LIST_VERSION = PREFIX + "meta:tool-list-ver";
    public static final String TOOL_CATEGORIES = PREFIX + "tool:categories:active";
    public static final String RECHARGE_PACKAGES = PREFIX + "credit:packages:active";
    public static final String PUBLIC_CUSTOMER_SERVICE = PREFIX + "settings:public:customer-service";
    public static final String MODEL_VENDORS_ENABLED = PREFIX + "agent:model-vendors:enabled";

    private CacheNamespaces() {
    }

    public static String toolList(long version, String view, String queryHash) {
        return PREFIX + "tool:list:" + PUBLIC_TOOL_CONTRACT_VERSION + ":" + view
                + ":v" + version + ":" + queryHash;
    }

    public static String toolDetail(String toolCode) {
        return PREFIX + "tool:detail:" + PUBLIC_TOOL_CONTRACT_VERSION + ":" + normalizeToolCode(toolCode);
    }

    public static String toolDetail(long version, String toolCode) {
        return PREFIX + "tool:detail:" + PUBLIC_TOOL_CONTRACT_VERSION + ":v" + version
                + ":" + normalizeToolCode(toolCode);
    }

    private static String normalizeToolCode(String toolCode) {
        return toolCode == null ? "_" : toolCode.trim().toLowerCase();
    }
}
