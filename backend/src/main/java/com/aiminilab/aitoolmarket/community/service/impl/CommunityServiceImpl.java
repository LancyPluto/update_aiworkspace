package com.aiminilab.aitoolmarket.community.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostResponse;
import com.aiminilab.aitoolmarket.community.dto.PublicUserProfileResponse;
import com.aiminilab.aitoolmarket.community.dto.PublishPostRequest;
import com.aiminilab.aitoolmarket.community.dto.UpdateCommunityPostRequest;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CommunityServiceImpl implements CommunityService {

    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_TOPIC_LENGTH = 64;
    private static final int MAX_TAG_LENGTH = 32;
    private static final int MAX_TAGS = 6;

    private final CommunityPostMapper postMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public CommunityServiceImpl(CommunityPostMapper postMapper, TaskMapper taskMapper,
                                UserMapper userMapper, ObjectMapper objectMapper) {
        this.postMapper = postMapper;
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void autoPublishTask(AiTask task, String resourceType, String contentText) {
        if (task == null || task.getId() == null || task.getUserId() == null) {
            return;
        }
        User user = userMapper.findById(task.getUserId()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getAutoPublishAssets())) {
            return;
        }
        if (postMapper.findByTaskId(task.getId()).isPresent()) {
            return;
        }
        createPost(task, resourceType, contentText, task.getToolName(), null,
                Boolean.TRUE.equals(user.getPromptPublicByDefault()), "PUBLISHED");
    }

    @Override
    @Transactional
    public CommunityPostResponse publish(Long userId, PublishPostRequest request) {
        if (request == null || request.taskId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "taskId is required");
        }
        AiTask task = taskMapper.findByIdAndUserId(request.taskId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "Task not found"));
        if (!TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "Only successful tasks can be published");
        }
        Optional<CommunityPost> existing = postMapper.findByTaskId(task.getId());
        if (existing.isPresent()) {
            CommunityPost post = existing.get();
            if (!post.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Post owner mismatch");
            }
            postMapper.updateOwnerStatus(post.getId(), userId, "PUBLISHED");
            postMapper.updateOwnerMetadata(
                    post.getId(),
                    userId,
                    normalizeTitle(request.title(), post.getTitle()),
                    normalizeDescription(request.description()),
                    Boolean.TRUE.equals(request.promptVisible()),
                    normalizeTopic(request.topic())
            );
            replaceTags(post.getId(), request.tags());
            return response(requirePost(post.getId()), userId);
        }
        String title = normalizeTitle(request.title(), task.getToolName());
        boolean promptVisible = Boolean.TRUE.equals(request.promptVisible());
        CommunityPost post = createPost(task, null, null, title, request.description(), promptVisible, "PUBLISHED");
        postMapper.updateTopic(post.getId(), normalizeTopic(request.topic()));
        replaceTags(post.getId(), request.tags());
        return response(requirePost(post.getId()), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse update(Long userId, Long postId, UpdateCommunityPostRequest request) {
        CommunityPost post = requireOwnedPost(postId, userId);
        String title = normalizeTitle(request == null ? null : request.title(), post.getTitle());
        String description = normalizeDescription(request == null ? null : request.description());
        boolean promptVisible = request == null || request.promptVisible() == null
                ? Boolean.TRUE.equals(post.getPromptVisible())
                : Boolean.TRUE.equals(request.promptVisible());
        String topic = request == null ? post.getTopic() : normalizeTopic(request.topic());
        if (postMapper.updateOwnerMetadata(postId, userId, title, description, promptVisible, topic) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
        if (request != null && request.tags() != null) {
            replaceTags(postId, request.tags());
        }
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public void unpublish(Long userId, Long postId) {
        requireOwnedPost(postId, userId);
        if (postMapper.updateOwnerStatus(postId, userId, "UNPUBLISHED") == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
    }

    @Override
    public PublicUserProfileResponse publicUser(Long userId) {
        User user = userMapper.findById(userId)
                .filter(u -> u.getDeleted() == null || !u.getDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
        return new PublicUserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                postMapper.countPublicByUserId(userId, null),
                postMapper.sumLikesByUserId(userId),
                postMapper.sumFavoritesByUserId(userId)
        );
    }

    @Override
    public PageResponse<CommunityPostResponse> discover(String modality, String tag, String topic, String sort,
                                                        Boolean featured, Long viewerId, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        String normalizedModality = normalizeModality(modality);
        String normalizedSort = normalizeSort(sort);
        String normalizedTag = normalizeTag(tag);
        String normalizedTopic = normalizeTopic(topic);
        List<CommunityPostResponse> list = postMapper.discover(
                        normalizedModality,
                        normalizedTag,
                        normalizedTopic,
                        normalizedSort,
                        featured,
                        normalizedPageSize,
                        offset
                )
                .stream()
                .map(post -> response(post, viewerId))
                .toList();
        long total = postMapper.countDiscover(normalizedModality, normalizedTag, normalizedTopic, featured);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public PageResponse<CommunityPostResponse> publicPosts(Long userId, String modality, Long viewerId,
                                                           Integer pageNo, Integer pageSize) {
        publicUser(userId);
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<CommunityPostResponse> list = postMapper.findPublicByUserId(userId, normalizeModality(modality), normalizedPageSize, offset)
                .stream()
                .map(post -> response(post, viewerId))
                .toList();
        long total = postMapper.countPublicByUserId(userId, normalizeModality(modality));
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    @Transactional
    public CommunityPostResponse detail(Long postId, Long viewerId) {
        CommunityPost post = requirePost(postId);
        if (!"PUBLISHED".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
        postMapper.incrementViews(postId);
        return response(requirePost(postId), viewerId);
    }

    @Override
    @Transactional
    public CommunityPostResponse markSameStyle(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.incrementSameStyle(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse like(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.insertLike(postId, userId);
        postMapper.refreshLikeCount(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse unlike(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.deleteLike(postId, userId);
        postMapper.refreshLikeCount(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse favorite(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.insertFavorite(postId, userId);
        postMapper.refreshFavoriteCount(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse unfavorite(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.deleteFavorite(postId, userId);
        postMapper.refreshFavoriteCount(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    public PageResponse<CommunityPostResponse> adminList(Long userId, String status, String modality, String keyword,
                                                         String topic, Boolean featured, Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedTopic = normalizeTopic(topic);
        List<CommunityPostResponse> list = postMapper.findForAdmin(userId, normalizeStatus(status), normalizeModality(modality),
                        normalizedKeyword, normalizedTopic, featured, normalizedPageSize, offset)
                .stream()
                .map(post -> response(post, null))
                .toList();
        long total = postMapper.countForAdmin(userId, normalizeStatus(status), normalizeModality(modality),
                normalizedKeyword, normalizedTopic, featured);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminHide(Long postId, String reason) {
        requirePost(postId);
        postMapper.updateAdminStatus(postId, "HIDDEN", "REJECTED", normalizeAuditReason(reason));
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminRestore(Long postId) {
        requirePost(postId);
        postMapper.updateAdminStatus(postId, "PUBLISHED", "APPROVED", null);
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminFeature(Long postId, boolean featured) {
        requirePost(postId);
        postMapper.updateFeatured(postId, featured);
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminPin(Long postId, boolean pinned) {
        requirePost(postId);
        postMapper.updatePinned(postId, pinned);
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminAnnotate(Long postId, String topic, List<String> tags) {
        requirePost(postId);
        postMapper.updateTopic(postId, normalizeTopic(topic));
        replaceTags(postId, tags);
        return response(requirePost(postId), null);
    }

    private CommunityPost createPost(AiTask task, String resourceType, String contentText, String title,
                                     String description, boolean promptVisible, String status) {
        String resultText = contentText;
        String resultType = resourceType;
        if (resultText == null || resultText.isBlank()) {
            var result = taskMapper.findFirstResult(task.getId()).orElse(null);
            if (result != null) {
                resultText = result.contentText();
                resultType = result.resourceType();
            }
        }
        CommunityPost post = new CommunityPost();
        post.setUserId(task.getUserId());
        post.setTaskId(task.getId());
        post.setModality(resolveModality(task, resultType));
        post.setCoverUrl(extractCoverUrl(resultText));
        post.setTitle(normalizeTitle(title, task.getToolName()));
        post.setDescription(normalizeDescription(description));
        post.setPromptVisible(promptVisible);
        post.setPromptSnapshot(extractPrompt(task.getParamsJson()));
        post.setToolCode(task.getToolCode());
        post.setToolName(task.getToolName());
        post.setStatus(status);
        post.setFeatured(false);
        post.setPinned(false);
        post.setTopic(null);
        post.setSameStyleCount(0L);
        post.setAuditStatus("APPROVED");
        post.setAuditReason(null);
        post.setViewCount(0L);
        post.setLikeCount(0L);
        post.setFavoriteCount(0L);
        postMapper.insertAndReturnId(post);
        return requirePost(post.getId());
    }

    private CommunityPost requirePost(Long postId) {
        return postMapper.findPostById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Post not found"));
    }

    private CommunityPost requireOwnedPost(Long postId, Long userId) {
        CommunityPost post = requirePost(postId);
        if (!post.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Post owner mismatch");
        }
        return post;
    }

    private CommunityPost requirePublished(Long postId) {
        CommunityPost post = requirePost(postId);
        if (!"PUBLISHED".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
        return post;
    }

    private CommunityPostResponse response(CommunityPost post, Long viewerId) {
        boolean liked = viewerId != null && postMapper.countLike(post.getId(), viewerId) > 0;
        boolean favorited = viewerId != null && postMapper.countFavorite(post.getId(), viewerId) > 0;
        return CommunityPostResponse.from(post, liked, favorited, postMapper.findTags(post.getId()));
    }

    private String resolveModality(AiTask task, String resourceType) {
        String output = task.getOutputModality();
        String type = output == null || output.isBlank() ? resourceType : output;
        if (type == null || type.isBlank()) {
            return "TEXT";
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("IMAGE")) return "IMAGE";
        if (normalized.contains("VIDEO")) return "VIDEO";
        if (normalized.contains("AUDIO")) return "AUDIO";
        return "TEXT";
    }

    private String normalizeTitle(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            normalized = fallback == null || fallback.isBlank() ? "AI creation" : fallback.trim();
        }
        return limit(normalized, MAX_TITLE_LENGTH);
    }

    private String normalizeDescription(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), MAX_DESCRIPTION_LENGTH);
    }

    private String normalizeModality(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStatus(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSort(String value) {
        if (value == null || value.trim().isBlank()) {
            return "LATEST";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return List.of("LATEST", "POPULAR", "FAVORITES", "SAME_STYLE", "VIEWS").contains(normalized)
                ? normalized
                : "LATEST";
    }

    private String normalizeTopic(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), MAX_TOPIC_LENGTH);
    }

    private String normalizeKeyword(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), 80);
    }

    private String normalizeAuditReason(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), 255);
    }

    private String normalizeTag(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), MAX_TAG_LENGTH);
    }

    private void replaceTags(Long postId, List<String> values) {
        postMapper.deleteTags(postId);
        if (values == null || values.isEmpty()) {
            return;
        }
        values.stream()
                .map(this::normalizeTag)
                .filter(tag -> tag != null && !tag.isBlank())
                .distinct()
                .limit(MAX_TAGS)
                .forEach(tag -> postMapper.insertTag(postId, tag));
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String extractPrompt(String paramsJson) {
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            if (root.isTextual()) {
                root = objectMapper.readTree(root.asText());
            }
            JsonNode sanitized = sanitizePromptSnapshot(root);
            for (String key : List.of("prompt", "description", "text", "content", "message")) {
                JsonNode node = sanitized.path(key);
                if (node.isTextual() && !node.asText().isBlank()) {
                    return limit(node.asText().trim(), MAX_PROMPT_LENGTH);
                }
            }
            return limit(sanitized.toString(), MAX_PROMPT_LENGTH);
        } catch (Exception ignored) {
            return "";
        }
    }

    private JsonNode sanitizePromptSnapshot(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return objectMapper.nullNode();
        }
        if (node.isArray()) {
            var out = objectMapper.createArrayNode();
            node.forEach(child -> out.add(sanitizePromptSnapshot(child)));
            return out;
        }
        if (!node.isObject()) {
            return node;
        }
        var out = objectMapper.createObjectNode();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.contains("key")
                    || normalized.contains("secret")
                    || normalized.contains("token")
                    || normalized.contains("password")
                    || normalized.contains("file")
                    || normalized.contains("upload")
                    || normalized.contains("url")
                    || normalized.contains("path")) {
                continue;
            }
            out.set(key, sanitizePromptSnapshot(entry.getValue()));
        }
        return out;
    }

    private String extractCoverUrl(String contentText) {
        if (contentText == null || contentText.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(contentText);
            String fromJson = findUrl(root);
            if (fromJson != null) {
                return fromJson;
            }
        } catch (Exception ignored) {
            // Plain text results can still contain a URL.
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(https?://\\S+|/generated/\\S+)")
                .matcher(contentText);
        return matcher.find() ? trimUrl(matcher.group(1)) : null;
    }

    private String findUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (looksLikeMediaUrl(value)) {
                return value;
            }
        }
        if (node.isObject()) {
            for (String key : List.of("coverUrl", "url", "imageUrl", "videoUrl", "audioUrl", "src")) {
                JsonNode child = node.path(key);
                if (child.isTextual() && looksLikeMediaUrl(child.asText())) {
                    return child.asText();
                }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String found = findUrl(fields.next().getValue());
                if (found != null) return found;
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findUrl(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean looksLikeMediaUrl(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return (normalized.startsWith("http://") || normalized.startsWith("https://") || normalized.startsWith("/generated/"))
                && (normalized.contains(".png") || normalized.contains(".jpg") || normalized.contains(".jpeg")
                || normalized.contains(".webp") || normalized.contains(".gif") || normalized.contains(".mp4")
                || normalized.contains(".webm") || normalized.contains(".mp3") || normalized.contains(".wav"));
    }

    private String trimUrl(String value) {
        return value == null ? null : value.replaceAll("[\\])},.]+$", "");
    }
}
