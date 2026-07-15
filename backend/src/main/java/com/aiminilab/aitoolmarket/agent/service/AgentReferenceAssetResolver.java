package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.InternalReferenceMentionResponse;
import com.aiminilab.aitoolmarket.storage.PrivateAssetAccessService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentReferenceAssetResolver {

    private final PrivateAssetAccessService privateAssetAccessService;

    public AgentReferenceAssetResolver(PrivateAssetAccessService privateAssetAccessService) {
        this.privateAssetAccessService = privateAssetAccessService;
    }

    public List<InternalReferenceMentionResponse> resolve(
            Long userId,
            List<InternalReferenceMentionResponse> mentions
    ) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }
        return mentions.stream()
                .map(mention -> resolveMention(userId, mention))
                .toList();
    }

    private InternalReferenceMentionResponse resolveMention(
            Long userId,
            InternalReferenceMentionResponse mention
    ) {
        String url = mention.url();
        if (privateAssetAccessService.privateRelativeKey(url) == null) {
            return mention;
        }
        return new InternalReferenceMentionResponse(
                mention.token(),
                mention.refLabel(),
                mention.assetKey(),
                privateAssetAccessService.resolveForWorker(userId, url),
                mention.kind(),
                mention.source()
        );
    }
}
