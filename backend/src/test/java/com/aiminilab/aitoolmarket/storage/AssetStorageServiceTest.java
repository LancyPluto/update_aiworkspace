package com.aiminilab.aitoolmarket.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        byte[] data = "hello".getBytes();
        StoredAsset stored = service.storeBytes("uploads/20260611/demo.txt", data, "text/plain");
        String expectedKey = AssetStorageService.contentHashKey("uploads/20260611/demo.txt", data);
        assertEquals("/generated/" + expectedKey, stored.publicUrl());
        assertTrue(Files.exists(tempDir.resolve(expectedKey)));
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

    @Test
    void generatesOneHourPrivateOssUrlForWorkerAtReadTime() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setOssPrivateBucket("wlcloudai-assets-private");
        properties.getAssetStorage().setOssPublicBucket("wlcloudai-assets-public");
        properties.getAssetStorage().setOssKeyPrefix("prod");
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        when(oss.generatePresignedUrl(
                org.mockito.ArgumentMatchers.eq("wlcloudai-assets-private"),
                org.mockito.ArgumentMatchers.eq("prod/uploads/20260618/a.png"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new URL("https://private.example/signed-a.png"));

        String signed = service.generateSignedPrivateUrl("uploads/20260618/a.png");

        assertEquals("https://private.example/signed-a.png", signed);
        verify(oss).generatePresignedUrl(
                org.mockito.ArgumentMatchers.eq("wlcloudai-assets-private"),
                org.mockito.ArgumentMatchers.eq("prod/uploads/20260618/a.png"),
                org.mockito.ArgumentMatchers.argThat(expiration -> {
                    long remaining = expiration.getTime() - System.currentTimeMillis();
                    return remaining > 3_500_000L && remaining <= 3_600_000L;
                })
        );
    }
}
