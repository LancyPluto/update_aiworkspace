package com.aiminilab.aitoolmarket.community.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityCollectionResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityCreatorResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityEventRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityStatsResponse;
import com.aiminilab.aitoolmarket.community.dto.PublicUserProfileResponse;
import com.aiminilab.aitoolmarket.community.dto.PublishPostRequest;
import com.aiminilab.aitoolmarket.community.dto.UpdateCommunityPostRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;

public interface CommunityService {
    void autoPublishTask(AiTask task, String resourceType, String contentText);
    CommunityPostResponse publish(Long userId, PublishPostRequest request);
    CommunityPostResponse update(Long userId, Long postId, UpdateCommunityPostRequest request);
    void unpublish(Long userId, Long postId);
    PublicUserProfileResponse publicUser(Long userId);
    PageResponse<CommunityPostResponse> discover(String modality, String tag, String topic, String sort, Boolean featured, Long viewerId, Integer pageNo, Integer pageSize);
    PageResponse<CommunityPostResponse> search(String keyword, String modality, String tag, String topic, String toolCode, String sort, Boolean featured, Long viewerId, Integer pageNo, Integer pageSize);
    PageResponse<CommunityPostResponse> topicPosts(String topic, String modality, String sort, Long viewerId, Integer pageNo, Integer pageSize);
    CommunityCreatorResponse creator(Long userId, Long viewerId);
    PageResponse<CommunityPostResponse> publicPosts(Long userId, String modality, Long viewerId, Integer pageNo, Integer pageSize);
    CommunityPostResponse detail(Long postId, Long viewerId);
    void recordEvent(Long userId, CommunityEventRequest request);
    java.util.List<CommunityCollectionResponse> collections(Long userId);
    CommunityCollectionResponse createCollection(Long userId, String name);
    CommunityCollectionResponse renameCollection(Long userId, Long collectionId, String name);
    void deleteCollection(Long userId, Long collectionId);
    CommunityCollectionResponse addCollectionItem(Long userId, Long collectionId, Long postId);
    void removeCollectionItem(Long userId, Long collectionId, Long postId);
    CommunityPostResponse markSameStyle(Long userId, Long postId);
    CommunityPostResponse like(Long userId, Long postId);
    CommunityPostResponse unlike(Long userId, Long postId);
    CommunityPostResponse favorite(Long userId, Long postId);
    CommunityPostResponse unfavorite(Long userId, Long postId);
    PageResponse<CommunityPostResponse> adminList(Long userId, String status, String modality, String keyword, String topic, Boolean featured, String auditStatus, Integer pageNo, Integer pageSize);
    CommunityStatsResponse adminStats();
    CommunityPostResponse adminHide(Long postId, String reason);
    CommunityPostResponse adminRestore(Long postId);
    CommunityPostResponse adminApprove(Long postId);
    CommunityPostResponse adminReject(Long postId, String reason);
    CommunityPostResponse adminFeature(Long postId, boolean featured);
    CommunityPostResponse adminPin(Long postId, boolean pinned);
    CommunityPostResponse adminAnnotate(Long postId, String topic, java.util.List<String> tags);
}
