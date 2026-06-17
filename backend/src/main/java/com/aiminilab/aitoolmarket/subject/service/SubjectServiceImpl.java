package com.aiminilab.aitoolmarket.subject.service;

import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.subject.dto.CreateSubjectRequest;
import com.aiminilab.aitoolmarket.subject.dto.SubjectResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncContextResponse;
import com.aiminilab.aitoolmarket.subject.dto.SubjectSyncResultRequest;
import com.aiminilab.aitoolmarket.subject.entity.GenerationSubject;
import com.aiminilab.aitoolmarket.subject.mapper.GenerationSubjectMapper;
import com.aiminilab.aitoolmarket.subject.support.SubjectVendorAccountResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SubjectServiceImpl implements SubjectService {

    private static final String PROVIDER_KLING = "kling_video";
    private static final String DEFAULT_KLING_BASE_URL = "https://api-beijing.klingai.com";
    private static final String REF_IMAGE = "image_refer";
    private static final String REF_VIDEO = "video_refer";
    private static final int MAX_ELEMENT_NAME_LENGTH = 20;
    private static final int MAX_ELEMENT_DESCRIPTION_LENGTH = 100;
    private static final int MAX_REFER_IMAGES = 3;
    private static final int MAX_REFER_VIDEOS = 1;

    private final GenerationSubjectMapper subjectMapper;
    private final SubjectVendorAccountResolver vendorAccountResolver;
    private final SubjectQueuePublisher subjectQueuePublisher;
    private final ObjectMapper objectMapper;

    public SubjectServiceImpl(GenerationSubjectMapper subjectMapper,
                              SubjectVendorAccountResolver vendorAccountResolver,
                              SubjectQueuePublisher subjectQueuePublisher,
                              ObjectMapper objectMapper) {
        this.subjectMapper = subjectMapper;
        this.vendorAccountResolver = vendorAccountResolver;
        this.subjectQueuePublisher = subjectQueuePublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<SubjectResponse> list(Long userId, String providerCode, String syncStatus, int pageNo, int pageSize) {
        int normalizedPageNo = Math.max(pageNo, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (normalizedPageNo - 1) * normalizedPageSize;
        List<SubjectResponse> items = subjectMapper
                .findByUser(userId, normalize(providerCode), normalize(syncStatus), offset, normalizedPageSize)
                .stream()
                .map(subject -> SubjectResponse.from(subject, objectMapper))
                .toList();
        long total = subjectMapper.countByUser(userId, normalize(providerCode), normalize(syncStatus));
        return PageResponse.of(items, total, normalizedPageNo, normalizedPageSize);
    }

    @Override
    public SubjectResponse detail(Long userId, String subjectCode) {
        return SubjectResponse.from(requireSubject(userId, subjectCode), objectMapper);
    }

    @Override
    public SubjectResponse create(Long userId, CreateSubjectRequest request) {
        validateElementText(request);
        String referenceType = normalizeReferenceType(request.referenceType());
        validateReference(referenceType, request.referenceJson());
        String vendorAccountRef = vendorAccountResolver.normalizeRef(request.vendorAccountRef());
        vendorAccountResolver.resolve(vendorAccountRef);

        GenerationSubject subject = new GenerationSubject();
        subject.setUserId(userId);
        subject.setSubjectCode(generateSubjectCode());
        subject.setDisplayName(request.displayName().trim());
        subject.setDescription(trimToNull(request.description()));
        subject.setProviderCode(PROVIDER_KLING);
        subject.setVendorAccountRef(vendorAccountRef);
        subject.setReferenceType(referenceType);
        subject.setPreviewUrl(resolvePreviewUrl(referenceType, request.referenceJson()));
        subject.setReferenceJson(writeJson(request.referenceJson()));
        subject.setSyncStatus("PENDING");
        subject.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        subject.setCreatedAt(now);
        subject.setUpdatedAt(now);
        subjectMapper.insertSubject(subject);
        publishSync(subject);
        return SubjectResponse.from(subject, objectMapper);
    }

    @Override
    public SubjectResponse retrySync(Long userId, String subjectCode) {
        GenerationSubject subject = requireSubject(userId, subjectCode);
        if ("READY".equalsIgnoreCase(subject.getSyncStatus())) {
            return SubjectResponse.from(subject, objectMapper);
        }
        subject.setSyncStatus("PENDING");
        subject.setSyncError(null);
        subject.setUpdatedAt(LocalDateTime.now());
        subjectMapper.updateSyncState(
                userId,
                subjectCode,
                "PENDING",
                subject.getSyncTaskId(),
                subject.getUpstreamElementId(),
                null,
                subject.getUpdatedAt()
        );
        publishSync(subject);
        return detail(userId, subjectCode);
    }

    @Override
    public void delete(Long userId, String subjectCode) {
        GenerationSubject subject = requireSubject(userId, subjectCode);
        int updated = subjectMapper.softDelete(userId, subjectCode, LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "主体不存在");
        }
        if (subject.getUpstreamElementId() != null && !subject.getUpstreamElementId().isBlank()) {
            subjectQueuePublisher.publishDelete(subjectCode, userId, subject.getUpstreamElementId());
        }
    }

    @Override
    public SubjectSyncContextResponse syncContext(String subjectCode) {
        GenerationSubject subject = subjectMapper.findActiveByCodeOnly(subjectCode);
        if (subject == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "主体不存在");
        }
        ModelVendorAccount account = vendorAccountResolver.resolve(subject.getVendorAccountRef());
        JsonNode referenceJson;
        try {
            referenceJson = objectMapper.readTree(subject.getReferenceJson());
        } catch (Exception ex) {
            referenceJson = objectMapper.createObjectNode();
        }
        return new SubjectSyncContextResponse(
                subject.getSubjectCode(),
                subject.getUserId(),
                subject.getDisplayName(),
                subject.getDescription(),
                subject.getProviderCode(),
                subject.getVendorAccountRef(),
                subject.getReferenceType(),
                referenceJson,
                firstNonBlank(account.getBaseUrl(), DEFAULT_KLING_BASE_URL),
                account.getApiKey(),
                account.getExtraAuthJson()
        );
    }

    @Override
    public void applySyncResult(String subjectCode, SubjectSyncResultRequest request) {
        GenerationSubject subject = findByCodeAnyUser(subjectCode);
        if (subject == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "主体不存在");
        }
        String syncStatus = request.syncStatus().trim().toUpperCase(Locale.ROOT);
        subjectMapper.updateSyncState(
                subject.getUserId(),
                subjectCode,
                syncStatus,
                trimToNull(request.syncTaskId()),
                trimToNull(request.upstreamElementId()),
                trimToNull(request.syncError()),
                LocalDateTime.now()
        );
    }

    private GenerationSubject requireSubject(Long userId, String subjectCode) {
        GenerationSubject subject = subjectMapper.findActiveByCode(userId, subjectCode);
        if (subject == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "主体不存在");
        }
        return subject;
    }

    private GenerationSubject findByCodeAnyUser(String subjectCode) {
        return subjectMapper.findActiveByCodeOnly(subjectCode);
    }

    private void publishSync(GenerationSubject subject) {
        if (!subjectQueuePublisher.publish(subject.getSubjectCode(), subject.getUserId())) {
            subjectMapper.updateSyncState(
                    subject.getUserId(),
                    subject.getSubjectCode(),
                    "FAILED",
                    null,
                    null,
                    "同步任务发布失败，请稍后重试",
                    LocalDateTime.now()
            );
        }
    }

    private void validateElementText(CreateSubjectRequest request) {
        String displayName = trimToNull(request.displayName());
        if (displayName == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "主体名称不能为空");
        }
        if (displayName.length() > MAX_ELEMENT_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "主体名称不能超过 20 个字符");
        }
        String description = trimToNull(request.description());
        if (description != null && description.length() > MAX_ELEMENT_DESCRIPTION_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "主体描述不能超过 100 个字符");
        }
    }

    private void validateReference(String referenceType, JsonNode referenceJson) {
        if (referenceJson == null || referenceJson.isNull()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "referenceJson 不能为空");
        }
        if (REF_IMAGE.equals(referenceType)) {
            String frontal = textValue(referenceJson.get("frontalImage"));
            ArrayNode referImages = referenceJson.path("referImages").isArray()
                    ? (ArrayNode) referenceJson.get("referImages")
                    : null;
            int referImageCount = countNonBlank(referImages);
            if (frontal == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "图片主体需提供 frontalImage 正面图");
            }
            if (referImageCount == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "图片主体需至少提供 1 张参考图 referImages");
            }
            if (referImageCount > MAX_REFER_IMAGES) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "图片主体最多提供 3 张参考图 referImages");
            }
            return;
        }
        if (REF_VIDEO.equals(referenceType)) {
            ArrayNode referVideos = referenceJson.path("referVideos").isArray()
                    ? (ArrayNode) referenceJson.get("referVideos")
                    : null;
            int referVideoCount = countNonBlank(referVideos);
            if (referVideoCount == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "视频主体需提供 referVideos");
            }
            if (referVideoCount > MAX_REFER_VIDEOS) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "视频主体最多提供 1 个参考视频 referVideos");
            }
        }
    }

    private String resolvePreviewUrl(String referenceType, JsonNode referenceJson) {
        if (REF_IMAGE.equals(referenceType)) {
            String frontal = textValue(referenceJson.get("frontalImage"));
            if (frontal != null) {
                return frontal;
            }
            if (referenceJson.path("referImages").isArray() && referenceJson.get("referImages").size() > 0) {
                return textValue(referenceJson.get("referImages").get(0));
            }
            return null;
        }
        if (referenceJson.path("referVideos").isArray() && referenceJson.get("referVideos").size() > 0) {
            return textValue(referenceJson.get("referVideos").get(0));
        }
        return null;
    }

    private String normalizeReferenceType(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!REF_IMAGE.equals(normalized) && !REF_VIDEO.equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "referenceType 仅支持 image_refer 或 video_refer");
        }
        return normalized;
    }

    private static String generateSubjectCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "referenceJson 无效");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static int countNonBlank(ArrayNode nodes) {
        if (nodes == null) {
            return 0;
        }
        int count = 0;
        for (JsonNode node : nodes) {
            if (textValue(node) != null) {
                count++;
            }
        }
        return count;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }
}
