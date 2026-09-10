package com.aiminilab.aitoolmarket.agent.dto;

public record LangGraphCheckpointWriteResponse(String taskId, String taskPath, Integer writeIndex,
                                                String channelName, String valueType, String valueBase64) {}
