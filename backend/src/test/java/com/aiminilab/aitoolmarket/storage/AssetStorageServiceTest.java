package com.aiminilab.aitoolmarket.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.PutObjectRequest;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetStorageServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recognizesOnlyContentAddressedMediaKeysAsImmutable() {
        assertTrue(AssetStorageService.isContentAddressed("images/" + "a".repeat(40) + ".png"));
        assertTrue(AssetStorageService.isContentAddressed("video/" + "b".repeat(40) + ".preview-480p.mp4"));
        assertFalse(AssetStorageService.isContentAddressed("images/legacy-cover.png"));
        assertFalse(AssetStorageService.isContentAddressed("images/" + "c".repeat(39) + ".png"));
    }

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
        TransactionSynchronizationManager.initSynchronization();

        String migrated = service.moveUrlToPublic(
                "https://wlcloudai-assets-private.oss-cn-guangzhou.aliyuncs.com/tasks/1/result.png");

        assertEquals("https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/tasks/1/result.png", migrated);
        verify(oss).copyObject(argThat((CopyObjectRequest request) ->
                "wlcloudai-assets-private".equals(request.getSourceBucketName())
                        && "tasks/1/result.png".equals(request.getSourceKey())
                        && "wlcloudai-assets-public".equals(request.getDestinationBucketName())
                        && "tasks/1/result.png".equals(request.getDestinationKey())
                        && "public,max-age=300,must-revalidate".equals(request.getNewObjectMetadata().getCacheControl())));
        verify(oss, never()).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");

        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

        verify(oss).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");
    }

    @Test
    void removesUniqueLocalUploadWhenDatabaseTransactionRollsBack() {
        AppProperties properties = new AppProperties();
        properties.setGeneratedMediaDir(tempDir.toString());
        properties.getAssetStorage().setProvider("local");
        properties.getAssetStorage().setPublicBaseUrl("/generated");
        AssetStorageService service = new AssetStorageService(properties);
        TransactionSynchronizationManager.initSynchronization();

        StoredAsset stored = service.storeMultipartPrivateUnique(
                "uploads/unique-file.txt",
                new MockMultipartFile("file", "unique-file.txt", "text/plain", "temporary".getBytes())
        );
        Path storedPath = Path.of(stored.storagePath());
        assertTrue(Files.exists(storedPath));

        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertFalse(Files.exists(storedPath));
    }

    @Test
    void failsMoveWhenOssCopyFailsAndKeepsSourceObject() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setOssPublicBucket("wlcloudai-assets-public");
        properties.getAssetStorage().setOssPrivateBucket("wlcloudai-assets-private");
        properties.getAssetStorage().setPublicBaseUrl("https://wlcloudai-assets-public.oss.example.com");
        properties.getAssetStorage().setPrivateBaseUrl("https://wlcloudai-assets-private.oss.example.com");
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        when(oss.copyObject(any(CopyObjectRequest.class))).thenThrow(new RuntimeException("copy failed"));
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(BusinessException.class, () -> service.moveUrlToPublic(
                "https://wlcloudai-assets-private.oss.example.com/tasks/1/result.png"));

        verify(oss).deleteObject("wlcloudai-assets-public", "tasks/1/result.png");
        verify(oss, never()).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");
    }

    @Test
    void removesNewOssCopyWhenDatabaseTransactionRollsBack() throws Exception {
        AppProperties properties = ossProperties();
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        TransactionSynchronizationManager.initSynchronization();

        service.moveUrlToPublic(
                "https://wlcloudai-assets-private.oss.example.com/tasks/1/result.png");
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().get(0);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, synchronization.getOrder());
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(oss).deleteObject("wlcloudai-assets-public", "tasks/1/result.png");
        verify(oss, never()).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");
    }

    @Test
    void keepsPreexistingOssTargetWhenDatabaseTransactionRollsBack() throws Exception {
        AppProperties properties = ossProperties();
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        when(oss.doesObjectExist("wlcloudai-assets-public", "tasks/1/result.png")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.moveUrlToPublic(
                "https://wlcloudai-assets-private.oss.example.com/tasks/1/result.png");
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(oss, never()).copyObject(any(CopyObjectRequest.class));
        verify(oss, never()).deleteObject("wlcloudai-assets-public", "tasks/1/result.png");
        verify(oss, never()).deleteObject("wlcloudai-assets-private", "tasks/1/result.png");
    }

    @Test
    void usesAtomicOssCreateAndDeletesOwnedUploadOnRollback() throws Exception {
        AppProperties properties = ossProperties();
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        TransactionSynchronizationManager.initSynchronization();

        service.storeMultipartPrivateUnique(
                "uploads/unique-file.txt",
                new MockMultipartFile("file", "unique-file.txt", "text/plain", "temporary".getBytes())
        );

        verify(oss).putObject(argThat((PutObjectRequest request) ->
                "wlcloudai-assets-private".equals(request.getBucketName())
                        && "uploads/unique-file.txt".equals(request.getKey())
                        && "true".equals(request.getHeaders().get("x-oss-forbid-overwrite"))));
        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(oss).deleteObject("wlcloudai-assets-private", "uploads/unique-file.txt");
    }

    @Test
    void doesNotDeleteOssObjectWhenAtomicCreateFails() throws Exception {
        AppProperties properties = ossProperties();
        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        when(oss.putObject(any(PutObjectRequest.class))).thenThrow(new RuntimeException("already exists"));
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(BusinessException.class, () -> service.storeMultipartPrivateUnique(
                "uploads/unique-file.txt",
                new MockMultipartFile("file", "unique-file.txt", "text/plain", "temporary".getBytes())
        ));

        verify(oss, never()).deleteObject("wlcloudai-assets-private", "uploads/unique-file.txt");
    }

    private AppProperties ossProperties() {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setOssPublicBucket("wlcloudai-assets-public");
        properties.getAssetStorage().setOssPrivateBucket("wlcloudai-assets-private");
        properties.getAssetStorage().setPublicBaseUrl("https://wlcloudai-assets-public.oss.example.com");
        properties.getAssetStorage().setPrivateBaseUrl("https://wlcloudai-assets-private.oss.example.com");
        return properties;
    }

    @Test
    void resolvesLegacyToolCoverUrlToOssPublicBaseWhenObjectExists() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getAssetStorage().setProvider("oss");
        properties.getAssetStorage().setOssEndpoint("oss-cn-guangzhou.aliyuncs.com");
        properties.getAssetStorage().setOssPublicBucket("wlcloudai-assets-public");
        properties.getAssetStorage().setOssPrivateBucket("wlcloudai-assets-private");
        properties.getAssetStorage().setPublicBaseUrl(
                "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com"
        );

        AssetStorageService service = new AssetStorageService(properties);
        OSS oss = mock(OSS.class);
        Field field = AssetStorageService.class.getDeclaredField("ossClient");
        field.setAccessible(true);
        field.set(service, oss);
        when(oss.doesObjectExist("wlcloudai-assets-public", "tool-covers/kling-preview.mp4")).thenReturn(true);

        String legacy = "/generated/tool-covers/kling-preview.mp4";
        assertEquals(
                "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/tool-covers/kling-preview.mp4",
                service.resolveExistingPublicUrl(legacy)
        );
        assertEquals(
                "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/tool-covers/kling-preview.mp4",
                service.normalizeLegacyPublicUrl(legacy)
        );
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
