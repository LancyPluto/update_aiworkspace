package com.aiminilab.aitoolmarket.market.support;

import java.util.UUID;

public final class MarketIdGenerator {

    private MarketIdGenerator() {
    }

    public static String sessionId() {
        return "sess_" + shortUuid();
    }

    public static String messageId() {
        return "msg_" + shortUuid();
    }

    public static String fileId() {
        return "file_" + shortUuid();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
