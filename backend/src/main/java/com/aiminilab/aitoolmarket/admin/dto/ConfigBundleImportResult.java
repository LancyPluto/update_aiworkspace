package com.aiminilab.aitoolmarket.admin.dto;

import java.util.List;

public record ConfigBundleImportResult(
        int settings,
        int vendorAccounts,
        int modelConfigs,
        int categories,
        int tools,
        int fields,
        int prompts,
        int promptVersions,
        int workflows,
        List<String> warnings
) {
}
