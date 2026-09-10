package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record UpsertLangGraphCheckpointWritesRequest(@NotBlank String threadId, String checkpointNs,
    @NotBlank String checkpointId, @NotBlank String taskId, String taskPath, @NotEmpty List<Write> writes) {
  public record Write(Integer writeIndex, @NotBlank String channelName, @NotBlank String valueType, @NotBlank String valueBase64) {}
}
