package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.market.mapper.AiMarketFileMapper;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.tool.mapper.UserUploadAssetMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivateAssetAccessServiceTest {

    private final AppProperties properties = new AppProperties();
    private final UserUploadAssetMapper uploadMapper = mock(UserUploadAssetMapper.class);
    private final AiMarketFileMapper marketFileMapper = mock(AiMarketFileMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final AssetStorageService storageService = mock(AssetStorageService.class);
    private final AgentFileMapper agentFileMapper = mock(AgentFileMapper.class);
    private final PrivateAssetAccessService service = new PrivateAssetAccessService(
            properties, uploadMapper, marketFileMapper, taskMapper, storageService, agentFileMapper
    );

    @Test
    void taskGeneratedAssetsUseTaskOwnership() {
        when(taskMapper.countOwnedTask(71L, 2L)).thenReturn(1L);

        assertTrue(service.canAccess(2L, "images/71/image-1.png"));
        assertTrue(service.canAccess(2L, "audio/71/audio-1.mp3"));
        assertTrue(service.canAccess(2L, "video/71/video-1.mp4"));
        assertTrue(service.canAccess(2L, "digital-human/71/final.mp4"));
        assertFalse(service.canAccess(3L, "images/71/image-1.png"));
    }

    @Test
    void uploadedAndMarketFilesUseDatabaseOwnership() {
        when(uploadMapper.countActiveByUserAndRelativeKey(2L, "uploads/20260618/a.png")).thenReturn(1L);
        when(marketFileMapper.countByUserAndRelativeKey(2L, "market-files/2/source.pdf")).thenReturn(1L);

        assertTrue(service.canAccess(2L, "uploads/20260618/a.png"));
        assertTrue(service.canAccess(2L, "market-files/2/source.pdf"));
        assertFalse(service.canAccess(3L, "uploads/20260618/a.png"));
        assertFalse(service.canAccess(3L, "market-files/2/source.pdf"));
    }

    @Test
    void agentAttachmentOwnerIsEncodedInGeneratedKey() {
        when(agentFileMapper.countReadyByIdAndUser(91L, 2L)).thenReturn(1L);

        assertTrue(service.canAccess(2L, "agent-attachments/2/91-random.png"));
        assertFalse(service.canAccess(3L, "agent-attachments/2/91-random.png"));
    }

    @Test
    void unknownPrivateKeyTypeIsDenied() {
        assertFalse(service.canAccess(2L, "misc/unowned.png"));
        assertFalse(service.canAccess(2L, "../secret.txt"));
    }
}
