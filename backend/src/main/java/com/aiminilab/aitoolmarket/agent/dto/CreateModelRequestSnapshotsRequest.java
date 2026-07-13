package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateModelRequestSnapshotsRequest(
        @NotEmpty @Size(max = 50) List<@Valid CreateModelRequestSnapshotRequest> snapshots
) {}
