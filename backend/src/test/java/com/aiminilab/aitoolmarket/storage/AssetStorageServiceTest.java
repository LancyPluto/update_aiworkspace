package com.aiminilab.aitoolmarket.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void ossWithoutExplicitPrivateBaseUsesBackendProxy() {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setPublicBaseUrl(
                "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com"
        );
        properties.getAssetStorage().setPrivateBaseUrl("");

        assertEquals("/api/v1/assets/private", properties.getAssetStorage().getPrivateBaseUrl());
    }

    @Test
    void movesPrivateOssUrlToPublicBucketAndReturnsPublicUrl() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setOssEndpoint("oss-cn-guangzhou.aliyuncs.com");
        properties.getAssetStorage().setOssPublicBucket("wlcloudai-assets-public");
        properties.getAssetStorage().setOssPrivateBucket("wlcloudai-assets-private");
        properties.getAssetStorage().setPublicBaseUrl("https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com");
        properties.getAssetStorage().setPrivateBaseUrl("https://wlcloudai-assets-private.oss-cn-guangzhou.aliyuncs.com");
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);

        String migrated = service.moveUrlToPublic(
                "https://wlcloudai-assets-private.oss-cn-guangzhou.aliyuncs.com/tasks/1/result.png");

        assertEquals("https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/tasks/1/result.png", migrated);
        verify(oss).copyObject(argThat((CopyObjectRequest request) ->
                "wlcloudai-assets-private".equals(request.getSourceBucketName())
                        && "tasks/1/result.png".equals(request.getSourceKey())
                        && "wlcloudai-assets-public".equals(request.getDestinationBucketName())
                        && "tasks/1/result.png".equals(request.getDestinationKey())));
        verify(oss).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");
    }
}
