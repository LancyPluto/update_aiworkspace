package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.InternalReferenceMentionResponse;
import com.aiminilab.aitoolmarket.storage.PrivateAssetAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReferenceAssetResolverTest {

    @Test
    void resolvesPrivateMentionUrlsForAgentDownloadsAndKeepsPublicUrls() {
        PrivateAssetAccessService privateAssets = mock(PrivateAssetAccessService.class);
        AgentReferenceAssetResolver resolver = new AgentReferenceAssetResolver(privateAssets);
        String privateUrl = "/api/v1/assets/private/uploads/20260626/image.jpg?x-oss-process=image/format,webp";
        String signedUrl = "https://private-bucket.oss.example/image.jpg?signature=temporary";
        when(privateAssets.privateRelativeKey(privateUrl)).thenReturn("uploads/20260626/image.jpg");
        when(privateAssets.resolveForWorker(7L, privateUrl)).thenReturn(signedUrl);

        List<InternalReferenceMentionResponse> resolved = resolver.resolve(7L, List.of(
                mention("@image1", privateUrl),
                mention("@image2", "https://cdn.example/public.jpg")
        ));

        assertThat(resolved).extracting(InternalReferenceMentionResponse::url)
                .containsExactly(signedUrl, "https://cdn.example/public.jpg");
        verify(privateAssets).resolveForWorker(7L, privateUrl);
    }

    private InternalReferenceMentionResponse mention(String token, String url) {
        return new InternalReferenceMentionResponse(token, token, token, url, "image", "upload");
    }
}
