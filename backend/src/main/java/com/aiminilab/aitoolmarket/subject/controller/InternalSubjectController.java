package com.aiminilab.aitoolmarket.subject.controller;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncContextResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncResultRequest;
import com.aiminilab.aitoolmarket.subject.service.SubjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/v1/subjects")
public class InternalSubjectController {

    private final SubjectService subjectService;

    public InternalSubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping("/{subjectCode}/sync-context")
    public ApiResponse<SubjectSyncContextResponse> syncContext(@PathVariable String subjectCode) {
        return ApiResponse.success(subjectService.syncContext(subjectCode));
    }

    @PostMapping("/{subjectCode}/sync-result")
    public ApiResponse<Void> syncResult(@PathVariable String subjectCode,
                                        @Valid @RequestBody SubjectSyncResultRequest request) {
        subjectService.applySyncResult(subjectCode, request);
        return ApiResponse.success(null);
    }
}
