package com.aiminilab.aitoolmarket.ppt.engine;

import com.fasterxml.jackson.databind.JsonNode;

public interface PptEngineAdapter {
    String engineCode();

    EngineCapabilities capabilities();

    EngineProject createProject(EngineProjectRequest request);

    EngineSubmission submit(EngineJobRequest request);

    EngineSubmission reconcileSubmission(String externalProjectId, String idempotencyKey);

    EngineJobSnapshot query(String externalProjectId, String externalJobId);

    JsonNode projectSnapshot(String externalProjectId);

    boolean cancel(String externalProjectId, String externalJobId);

    byte[] download(String enginePath);
}
