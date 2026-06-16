package com.aiminilab.aitoolmarket.subject.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.subject.dto.CreateSubjectRequest;
import com.aiminilab.aitoolmarket.subject.dto.SubjectResponse;
import com.aiminilab.aitoolmarket.subject.service.SubjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping
    public ApiResponse<PageResponse<SubjectResponse>> list(
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", required = false, defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") int pageSize
    ) {
        return ApiResponse.success(subjectService.list(AuthContext.get().userId(), provider, status, pageNo, pageSize));
    }

    @GetMapping("/{subjectCode}")
    public ApiResponse<SubjectResponse> detail(@PathVariable String subjectCode) {
        return ApiResponse.success(subjectService.detail(AuthContext.get().userId(), subjectCode));
    }

    @PostMapping
    public ApiResponse<SubjectResponse> create(@Valid @RequestBody CreateSubjectRequest request) {
        return ApiResponse.success(subjectService.create(AuthContext.get().userId(), request));
    }

    @PostMapping("/{subjectCode}/sync")
    public ApiResponse<SubjectResponse> retrySync(@PathVariable String subjectCode) {
        return ApiResponse.success(subjectService.retrySync(AuthContext.get().userId(), subjectCode));
    }

    @DeleteMapping("/{subjectCode}")
    public ApiResponse<Void> delete(@PathVariable String subjectCode) {
        subjectService.delete(AuthContext.get().userId(), subjectCode);
        return ApiResponse.success(null);
    }
}
