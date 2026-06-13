package com.aiminilab.aitoolmarket.community.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostReportResponse;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/community/reports")
public class AdminCommunityReportController {

    private final CommunityService communityService;

    public AdminCommunityReportController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping
    public ApiResponse<PageResponse<CommunityPostReportResponse>> list(@RequestParam(required = false) String status,
                                                                       @RequestParam(required = false) Integer pageNo,
                                                                       @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(communityService.adminReports(status, pageNo, pageSize));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<CommunityPostReportResponse> resolve(@PathVariable Long reportId,
                                                            @RequestBody(required = false) ResolveReportRequest request) {
        return ApiResponse.success(communityService.adminResolveReport(
                reportId,
                request == null ? null : request.status(),
                request == null ? null : request.adminNote()
        ));
    }

    public record ResolveReportRequest(String status, String adminNote) {
    }
}
