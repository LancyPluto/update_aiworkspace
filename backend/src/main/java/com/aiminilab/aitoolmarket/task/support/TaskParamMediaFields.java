package com.aiminilab.aitoolmarket.task.support;

public final class TaskParamMediaFields {

    private TaskParamMediaFields() {
    }

    public static boolean looksLikeMediaField(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lowered = key.toLowerCase();
        return lowered.contains("image")
                || lowered.contains("frame")
                || lowered.contains("url")
                || lowered.contains("video")
                || lowered.contains("reference");
    }
}
