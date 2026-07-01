package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;

import java.util.List;

public interface ConfigBundleService {

    ConfigBundleDto exportBundle(Long operatorId, boolean includeSecrets);

    ConfigBundleDto exportBundle(Long operatorId, boolean includeSecrets, List<String> toolCodes, boolean includeMediaAssets);

    ConfigBundleImportResult importBundle(ConfigBundleDto bundle, Long operatorId);
}
