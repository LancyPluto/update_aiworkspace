package com.aiminilab.aitoolmarket.subject.service;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.subject.dto.CreateSubjectRequest;
import com.aiminilab.aitoolmarket.subject.dto.SubjectResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncContextResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncResultRequest;

public interface SubjectService {

    PageResponse<SubjectResponse> list(Long userId, String providerCode, String syncStatus, int pageNo, int pageSize);

    SubjectResponse detail(Long userId, String subjectCode);

    SubjectResponse create(Long userId, CreateSubjectRequest request);

    SubjectResponse retrySync(Long userId, String subjectCode);

    void delete(Long userId, String subjectCode);

    SubjectSyncContextResponse syncContext(String subjectCode);

    void applySyncResult(String subjectCode, SubjectSyncResultRequest request);
}
