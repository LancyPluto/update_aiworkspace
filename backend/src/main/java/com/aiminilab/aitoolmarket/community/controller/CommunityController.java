package com.aiminilab.aitoolmarket.community.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityCollectionItemRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityCollectionRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityCollectionResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityCreatorResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityEventRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostResponse;
import com.aiminilab.aitoolmarket.community.dto.PublicUserProfileResponse;
import com.aiminilab.aitoolmarket.community.dto.PublishPostRequest;
import com.aiminilab.aitoolmarket.community.dto.ReportCommunityPostRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityTopicResponse;
import com.aiminilab.aitoolmarket.community.dto.UpdateCommunityPostRequest;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<PublicUserProfileResponse> user(@PathVariable Long userId) {
        return ApiResponse.success(communityService.publicUser(userId));
    }

    @GetMapping("/users/{userId}/posts")
    public ApiResponse<PageResponse<CommunityPostResponse>> userPosts(@PathVariable Long userId,
                                                                      @RequestParam(required = false) String modality,
                                                                      @RequestParam(required = false) Integer pageNo,
                                                                      @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.publicPosts(userId, modality, currentUserIdOrNull(), pageNo, pageSize));
    }

    @GetMapping("/creators/{userId}")
    public ApiResponse<CommunityCreatorResponse> creator(@PathVariable Long userId) {
        return ApiResponse.success(communityService.creator(userId, currentUserIdOrNull()));
    }

    @GetMapping("/posts")
    public ApiResponse<PageResponse<CommunityPostResponse>> discover(@RequestParam(required = false) String modality,
                                                                     @RequestParam(required = false) String tag,
                                                                     @RequestParam(required = false) String topic,
                                                                     @RequestParam(required = false) String sort,
                                                                     @RequestParam(required = false) Boolean featured,
                                                                     @RequestParam(required = false) Integer pageNo,
                                                                     @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.discover(modality, tag, topic, sort, featured, currentUserIdOrNull(), pageNo, pageSize));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<CommunityPostResponse>> search(@RequestParam(required = false) String keyword,
                                                                   @RequestParam(required = false) String modality,
                                                                   @RequestParam(required = false) String tag,
                                                                   @RequestParam(required = false) String topic,
                                                                   @RequestParam(required = false) String toolCode,
                                                                   @RequestParam(required = false) String sort,
                                                                   @RequestParam(required = false) Boolean featured,
                                                                   @RequestParam(required = false) Integer pageNo,
                                                                   @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.search(keyword, modality, tag, topic, toolCode, sort, featured, currentUserIdOrNull(), pageNo, pageSize));
    }

    @GetMapping("/topics")
    public ApiResponse<java.util.List<CommunityTopicResponse>> topics(@RequestParam(required = false) Integer limit) {
        return ApiResponse.success(communityService.topics(limit));
    }

    @GetMapping("/topics/{topic}")
    public ApiResponse<PageResponse<CommunityPostResponse>> topic(@PathVariable String topic,
                                                                  @RequestParam(required = false) String modality,
                                                                  @RequestParam(required = false) String sort,
                                                                  @RequestParam(required = false) Integer pageNo,
                                                                  @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.topicPosts(topic, modality, sort, currentUserIdOrNull(), pageNo, pageSize));
    }

    @GetMapping("/posts/{postId}")
    public ApiResponse<CommunityPostResponse> detail(@PathVariable Long postId) {
        return ApiResponse.success(communityService.detail(postId, currentUserIdOrNull()));
    }

    @GetMapping("/posts/{postId}/download")
    public ResponseEntity<Void> downloadPost(@PathVariable Long postId,
                                             @RequestParam(required = false) Integer index) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(communityService.downloadPostMedia(postId, index))
                .build();
    }

    @PostMapping("/posts/{postId}/same-style")
    public ApiResponse<CommunityPostResponse> sameStyle(@PathVariable Long postId) {
        return ApiResponse.success(communityService.markSameStyle(AuthContext.get().userId(), postId));
    }

    @PostMapping("/events")
    public ApiResponse<Void> event(@RequestBody CommunityEventRequest request) {
        communityService.recordEvent(currentUserIdOrNull(), request);
        return ApiResponse.success(null);
    }

    @GetMapping("/collections")
    public ApiResponse<java.util.List<CommunityCollectionResponse>> collections() {
        return ApiResponse.success(communityService.collections(AuthContext.get().userId()));
    }

    @PostMapping("/collections")
    public ApiResponse<CommunityCollectionResponse> createCollection(@RequestBody(required = false) CommunityCollectionRequest request) {
        return ApiResponse.success(communityService.createCollection(AuthContext.get().userId(), request == null ? null : request.name()));
    }

    @PatchMapping("/collections/{collectionId}")
    public ApiResponse<CommunityCollectionResponse> renameCollection(@PathVariable Long collectionId,
                                                                     @RequestBody(required = false) CommunityCollectionRequest request) {
        return ApiResponse.success(communityService.renameCollection(AuthContext.get().userId(), collectionId, request == null ? null : request.name()));
    }

    @DeleteMapping("/collections/{collectionId}")
    public ApiResponse<Void> deleteCollection(@PathVariable Long collectionId) {
        communityService.deleteCollection(AuthContext.get().userId(), collectionId);
        return ApiResponse.success(null);
    }

    @PostMapping("/collections/{collectionId}/items")
    public ApiResponse<CommunityCollectionResponse> addCollectionItem(@PathVariable Long collectionId,
                                                                      @RequestBody CommunityCollectionItemRequest request) {
        return ApiResponse.success(communityService.addCollectionItem(AuthContext.get().userId(), collectionId, request.postId()));
    }

    @DeleteMapping("/collections/{collectionId}/items/{postId}")
    public ApiResponse<Void> removeCollectionItem(@PathVariable Long collectionId, @PathVariable Long postId) {
        communityService.removeCollectionItem(AuthContext.get().userId(), collectionId, postId);
        return ApiResponse.success(null);
    }

    @PostMapping("/posts")
    public ApiResponse<CommunityPostResponse> publish(@RequestBody PublishPostRequest request) {
        return ApiResponse.success(communityService.publish(AuthContext.get().userId(), request));
    }

    @PatchMapping("/posts/{postId}")
    public ApiResponse<CommunityPostResponse> update(@PathVariable Long postId,
                                                     @RequestBody UpdateCommunityPostRequest request) {
        return ApiResponse.success(communityService.update(AuthContext.get().userId(), postId, request));
    }

    @DeleteMapping("/posts/{postId}")
    public ApiResponse<Void> unpublish(@PathVariable Long postId) {
        communityService.unpublish(AuthContext.get().userId(), postId);
        return ApiResponse.success(null);
    }

    @PostMapping("/posts/{postId}/like")
    public ApiResponse<CommunityPostResponse> like(@PathVariable Long postId) {
        return ApiResponse.success(communityService.like(AuthContext.get().userId(), postId));
    }

    @DeleteMapping("/posts/{postId}/like")
    public ApiResponse<CommunityPostResponse> unlike(@PathVariable Long postId) {
        return ApiResponse.success(communityService.unlike(AuthContext.get().userId(), postId));
    }

    @PostMapping("/posts/{postId}/favorite")
    public ApiResponse<CommunityPostResponse> favorite(@PathVariable Long postId) {
        return ApiResponse.success(communityService.favorite(AuthContext.get().userId(), postId));
    }

    @DeleteMapping("/posts/{postId}/favorite")
    public ApiResponse<CommunityPostResponse> unfavorite(@PathVariable Long postId) {
        return ApiResponse.success(communityService.unfavorite(AuthContext.get().userId(), postId));
    }

    @PostMapping("/posts/{postId}/report")
    public ApiResponse<Void> report(@PathVariable Long postId,
                                    @RequestBody(required = false) ReportCommunityPostRequest request) {
        communityService.reportPost(AuthContext.get().userId(), postId, request);
        return ApiResponse.success(null);
    }

    private Long currentUserIdOrNull() {
        try {
            return AuthContext.get().userId();
        } catch (Exception ignored) {
            return null;
        }
    }
}
