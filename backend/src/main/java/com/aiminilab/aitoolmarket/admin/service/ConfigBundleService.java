package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleDto;
import com.aiminilab.aitoolmarket.admin.dto.ConfigBundleImportResult;

public interface ConfigBundleService {

    ConfigBundleDto exportBundle(Long operatorId);

    ConfigBundleImportResult importBundle(ConfigBundleDto bundle, Long operatorId);
}
