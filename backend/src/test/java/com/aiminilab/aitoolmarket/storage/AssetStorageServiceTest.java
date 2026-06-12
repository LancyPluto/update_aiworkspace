package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesFileLocallyAndReturnsGeneratedUrl() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setGeneratedMediaDir(tempDir.toString());
        properties.getAssetStorage().setProvider("local");
        properties.getAssetStorage().setPublicBaseUrl("/generated");

        AssetStorageService service = new AssetStorageService(properties);
        service.init();

        StoredAsset stored = service.storeBytes("uploads/20260611/demo.txt", "hello".getBytes(), "text/plain");
        assertEquals("/generated/uploads/20260611/demo.txt", stored.publicUrl());
        assertTrue(Files.exists(tempDir.resolve("uploads/20260611/demo.txt")));
        assertNotNull(service.resolveExistingPublicUrl(stored.publicUrl()));
    }
}
