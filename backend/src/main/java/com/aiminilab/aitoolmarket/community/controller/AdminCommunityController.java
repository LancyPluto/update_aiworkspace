package com.aiminilab.aitoolmarket.community.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostResponse;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/community/posts")
public class AdminCommunityController {

    private final CommunityService communityService;

    public AdminCommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping
    public ApiResponse<PageResponse<CommunityPostResponse>> list(@RequestParam(required = false) Long userId,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) String modality,
                                                                 @RequestParam(required = false) String keyword,
                                                                 @RequestParam(required = false) String topic,
                                                                 @RequestParam(required = false) Boolean featured,
                                                                 @RequestParam(required = false) Integer pageNo,
                                                                 @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.adminList(userId, status, modality, keyword, topic, featured, pageNo, pageSize));
    }

    @PostMapping("/{postId}/hide")
    public ApiResponse<CommunityPostResponse> hide(@PathVariable Long postId,
                                                  @RequestBody(required = false) AdminCommunityActionRequest request) {
        return ApiResponse.success(communityService.adminHide(postId, request == null ? null : request.reason()));
    }

    @PostMapping("/{postId}/restore")
    public ApiResponse<CommunityPostResponse> restore(@PathVariable Long postId) {
        return ApiResponse.success(communityService.adminRestore(postId));
    }

    @PostMapping("/{postId}/feature")
    public ApiResponse<CommunityPostResponse> feature(@PathVariable Long postId,
                                                     @RequestBody(required = false) AdminCommunityActionRequest request) {
        return ApiResponse.success(communityService.adminFeature(postId, request == null || request.enabled() == null || request.enabled()));
    }

    @PostMapping("/{postId}/pin")
    public ApiResponse<CommunityPostResponse> pin(@PathVariable Long postId,
                                                 @RequestBody(required = false) AdminCommunityActionRequest request) {
        return ApiResponse.success(communityService.adminPin(postId, request == null || request.enabled() == null || request.enabled()));
    }

    @PostMapping("/{postId}/annotate")
    public ApiResponse<CommunityPostResponse> annotate(@PathVariable Long postId,
                                                      @RequestBody(required = false) AdminCommunityActionRequest request) {
        return ApiResponse.success(communityService.adminAnnotate(
                postId,
                request == null ? null : request.topic(),
                request == null ? null : request.tags()
        ));
    }

    public record AdminCommunityActionRequest(Boolean enabled, String reason, String topic, java.util.List<String> tags) {
    }
}
