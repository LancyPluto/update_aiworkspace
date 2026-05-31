package com.aiminilab.aitoolmarket.community.service.impl;

import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.dto.CommunityCollectionResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityCreatorResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityEventRequest;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostDiscoverRow;
import com.aiminilab.aitoolmarket.community.dto.CommunityPostResponse;
import com.aiminilab.aitoolmarket.community.dto.CommunityStatsResponse;
import com.aiminilab.aitoolmarket.community.dto.PublicUserProfileResponse;
import com.aiminilab.aitoolmarket.community.dto.PublishPostRequest;
import com.aiminilab.aitoolmarket.community.dto.UpdateCommunityPostRequest;
import com.aiminilab.aitoolmarket.community.entity.CommunityCollection;
import com.aiminilab.aitoolmarket.community.entity.CommunityPost;
import com.aiminilab.aitoolmarket.community.mapper.CommunityCollectionMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityEventMapper;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CommunityServiceImpl implements CommunityService {

    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_TOPIC_LENGTH = 64;
    private static final int MAX_TAG_LENGTH = 32;
    private static final int MAX_TAGS = 6;
    private static final int MAX_COLLECTION_NAME_LENGTH = 80;

    private final CommunityPostMapper postMapper;
    private final CommunityCollectionMapper collectionMapper;
    private final CommunityEventMapper eventMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public CommunityServiceImpl(CommunityPostMapper postMapper, CommunityCollectionMapper collectionMapper,
                                CommunityEventMapper eventMapper, TaskMapper taskMapper,
                                UserMapper userMapper, ObjectMapper objectMapper) {
        this.postMapper = postMapper;
        this.collectionMapper = collectionMapper;
        this.eventMapper = eventMapper;
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
        List<CommunityPost> posts = postMapper.discover(
                        normalizedModality, normalizedTag, normalizedTopic, null, null,
                        normalizedSort, featured, normalizedPageSize, offset);
        List<CommunityPostResponse> list = responseBatch(posts, viewerId);
        long total = postMapper.countDiscover(normalizedModality, normalizedTag, normalizedTopic, null, null, featured);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public PageResponse<CommunityPostResponse> search(String keyword, String modality, String tag, String topic,
                                                      String toolCode, String sort, Boolean featured, Long viewerId,
                                                      Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        String normalizedModality = normalizeModality(modality);
        String normalizedTag = normalizeTag(tag);
        String normalizedTopic = normalizeTopic(topic);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedToolCode = normalizeToolCode(toolCode);
        String normalizedSort = normalizeSort(sort);
        List<CommunityPost> posts = postMapper.discover(
                        normalizedModality, normalizedTag, normalizedTopic, normalizedKeyword, normalizedToolCode,
                        normalizedSort, featured, normalizedPageSize, offset);
        List<CommunityPostResponse> list = responseBatch(posts, viewerId);
        long total = postMapper.countDiscover(normalizedModality, normalizedTag, normalizedTopic,
                normalizedKeyword, normalizedToolCode, featured);
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public PageResponse<CommunityPostResponse> topicPosts(String topic, String modality, String sort, Long viewerId,
                                                         Integer pageNo, Integer pageSize) {
        String normalizedTopic = normalizeTopic(topic);
        if (normalizedTopic == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "topic is required");
        }
        return search(null, modality, null, normalizedTopic, null, sort, null, viewerId, pageNo, pageSize);
    }

    @Override
    public CommunityCreatorResponse creator(Long userId, Long viewerId) {
        PublicUserProfileResponse profile = publicUser(userId);
        List<CommunityPostResponse> featured = postMapper.findFeaturedByUserId(userId, 6)
                .stream()
                .map(post -> response(post, viewerId))
                .toList();
        List<CommunityPostResponse> recent = postMapper.findPublicByUserId(userId, null, 12, 0)
                .stream()
                .map(post -> response(post, viewerId))
                .toList();
        return new CommunityCreatorResponse(
                profile,
                postMapper.sumSameStyleByUserId(userId),
                postMapper.countFeaturedByUserId(userId),
                featured,
                recent
        );
    }

    @Override
    public PageResponse<CommunityPostResponse> publicPosts(Long userId, String modality, Long viewerId,
                                                           Integer pageNo, Integer pageSize) {
        publicUser(userId);
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        List<CommunityPostDiscoverRow> rows = postMapper.findPublicByUserIdWithAuthor(
                userId, normalizeModality(modality), normalizedPageSize, offset);
        List<CommunityPostResponse> list = responseBatch(rows, viewerId);
        long total = postMapper.countPublicByUserId(userId, normalizeModality(modality));
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    @Transactional
    public CommunityPostResponse detail(Long postId, Long viewerId) {
        CommunityPost post = requirePost(postId);
        if (!isPubliclyVisible(post)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
        postMapper.incrementViews(postId);
        postMapper.incrementDetailClicks(postId);
        if (viewerId != null) {
            recordEvent(viewerId, new CommunityEventRequest(postId, "detail_view", "detail", post.getToolCode(), null, null));
        }
        return response(requirePost(postId), viewerId);
    }

    @Override
    @Transactional
    public void recordEvent(Long userId, CommunityEventRequest request) {
        if (request == null || request.eventType() == null || request.eventType().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "eventType is required");
        }
        Long postId = request.postId();
        if (postId != null) {
            CommunityPost post = requirePublished(postId);
            if ("share".equals(normalizeEventType(request.eventType()))) {
                postMapper.incrementShares(postId);
            }
            eventMapper.insertEvent(postId, userId, normalizeEventType(request.eventType()),
                    limitNullable(request.source(), 64),
                    normalizeToolCode(request.toolCode() == null ? post.getToolCode() : request.toolCode()),
                    request.taskId(),
                    request.credits() == null ? 0 : Math.max(request.credits(), 0));
            return;
        }
        eventMapper.insertEvent(null, userId, normalizeEventType(request.eventType()),
                limitNullable(request.source(), 64), normalizeToolCode(request.toolCode()),
                request.taskId(), request.credits() == null ? 0 : Math.max(request.credits(), 0));
    }

    @Override
    @Transactional
    public List<CommunityCollectionResponse> collections(Long userId) {
        ensureDefaultCollection(userId);
        return collectionMapper.findByUserId(userId).stream()
                .map(collection -> collectionResponse(collection, userId, 12))
                .toList();
    }

    @Override
    @Transactional
    public CommunityCollectionResponse createCollection(Long userId, String name) {
        CommunityCollection collection = new CommunityCollection();
        collection.setUserId(userId);
        collection.setName(normalizeCollectionName(name));
        collection.setDefaultCollection(false);
        collection.setItemCount(0L);
        collectionMapper.insertAndReturnId(collection);
        return collectionResponse(collectionMapper.findOwned(collection.getId(), userId), userId, 12);
    }

    @Override
    @Transactional
    public CommunityCollectionResponse renameCollection(Long userId, Long collectionId, String name) {
        requireOwnedCollection(collectionId, userId);
        if (collectionMapper.rename(collectionId, userId, normalizeCollectionName(name)) == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Default collection cannot be renamed");
        }
        return collectionResponse(requireOwnedCollection(collectionId, userId), userId, 12);
    }

    @Override
    @Transactional
    public void deleteCollection(Long userId, Long collectionId) {
        requireOwnedCollection(collectionId, userId);
        collectionMapper.removeAllItems(collectionId, userId);
        if (collectionMapper.deleteOwned(collectionId, userId) == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Default collection cannot be deleted");
        }
    }

    @Override
    @Transactional
    public CommunityCollectionResponse addCollectionItem(Long userId, Long collectionId, Long postId) {
        CommunityCollection collection = collectionId == null ? ensureDefaultCollection(userId) : requireOwnedCollection(collectionId, userId);
        requirePublished(postId);
        collectionMapper.addItem(collection.getId(), postId, userId);
        collectionMapper.refreshItemCount(collection.getId());
        recordEvent(userId, new CommunityEventRequest(postId, "favorite", "collection", null, null, null));
        return collectionResponse(requireOwnedCollection(collection.getId(), userId), userId, 24);
    }

    @Override
    @Transactional
    public void removeCollectionItem(Long userId, Long collectionId, Long postId) {
        CommunityCollection collection = requireOwnedCollection(collectionId, userId);
        collectionMapper.removeItem(collection.getId(), postId, userId);
        collectionMapper.refreshItemCount(collection.getId());
    }

    @Override
    @Transactional
    public CommunityPostResponse markSameStyle(Long userId, Long postId) {
        CommunityPost post = requirePublished(postId);
        postMapper.incrementSameStyle(postId);
        postMapper.refreshQualityScore(postId);
        recordEvent(userId, new CommunityEventRequest(postId, "same_style_click", "community", post.getToolCode(), null, null));
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse like(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.insertLike(postId, userId);
        postMapper.refreshLikeCount(postId);
        postMapper.refreshQualityScore(postId);
        recordEvent(userId, new CommunityEventRequest(postId, "like", "community", null, null, null));
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse unlike(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.deleteLike(postId, userId);
        postMapper.refreshLikeCount(postId);
        postMapper.refreshQualityScore(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse favorite(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.insertFavorite(postId, userId);
        postMapper.refreshFavoriteCount(postId);
        postMapper.refreshQualityScore(postId);
        recordEvent(userId, new CommunityEventRequest(postId, "favorite", "community", null, null, null));
        return response(requirePost(postId), userId);
    }

    @Override
    @Transactional
    public CommunityPostResponse unfavorite(Long userId, Long postId) {
        requirePublished(postId);
        postMapper.deleteFavorite(postId, userId);
        postMapper.refreshFavoriteCount(postId);
        postMapper.refreshQualityScore(postId);
        return response(requirePost(postId), userId);
    }

    @Override
    public PageResponse<CommunityPostResponse> adminList(Long userId, String status, String modality, String keyword,
                                                         String topic, Boolean featured, String auditStatus,
                                                         Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        String normalizedKeyword = normalizeKeyword(keyword);
        String normalizedTopic = normalizeTopic(topic);
        List<CommunityPost> posts = postMapper.findForAdmin(userId, normalizeStatus(status), normalizeModality(modality),
                        normalizedKeyword, normalizedTopic, featured, normalizeAuditStatus(auditStatus), normalizedPageSize, offset);
        List<CommunityPostResponse> list = responseBatch(posts, null);
        long total = postMapper.countForAdmin(userId, normalizeStatus(status), normalizeModality(modality),
                normalizedKeyword, normalizedTopic, featured, normalizeAuditStatus(auditStatus));
        return PageResponse.of(list, total, pageNo, pageSize);
    }

    @Override
    public CommunityStatsResponse adminStats() {
        return new CommunityStatsResponse(
                postMapper.countForStats(null, null),
                postMapper.countForStats(null, "PENDING"),
                postMapper.countForStats("HIDDEN", null),
                eventMapper.countByType("impression"),
                eventMapper.countByType("detail_view"),
                eventMapper.countByType("same_style_click"),
                eventMapper.countByType("task_created"),
                eventMapper.sumCredits(),
                eventMapper.topTools(8),
                eventMapper.topTopics(8),
                eventMapper.topCreators(8)
        );
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
    public CommunityPostResponse adminApprove(Long postId) {
        requirePost(postId);
        postMapper.updateAdminStatus(postId, "PUBLISHED", "APPROVED", null);
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminReject(Long postId, String reason) {
        requirePost(postId);
        postMapper.updateAdminStatus(postId, "HIDDEN", "REJECTED", normalizeAuditReason(reason));
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminFeature(Long postId, boolean featured) {
        requirePost(postId);
        postMapper.updateFeatured(postId, featured);
        postMapper.refreshQualityScore(postId);
        return response(requirePost(postId), null);
    }

    @Override
    @Transactional
    public CommunityPostResponse adminPin(Long postId, boolean pinned) {
        requirePost(postId);
        postMapper.updatePinned(postId, pinned);
        postMapper.refreshQualityScore(postId);
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
        post.setDetailClickCount(0L);
        post.setShareCount(0L);
        post.setQualityScore(0L);
        post.setLikeCount(0L);
        post.setFavoriteCount(0L);
        postMapper.insertAndReturnId(post);
        postMapper.refreshQualityScore(post.getId());
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
        if (!isPubliclyVisible(post)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Post not found");
        }
        return post;
    }

    private boolean isPubliclyVisible(CommunityPost post) {
        return post != null
                && "PUBLISHED".equals(post.getStatus())
                && (post.getAuditStatus() == null || "APPROVED".equals(post.getAuditStatus()));
    }

    private CommunityCollection ensureDefaultCollection(Long userId) {
        CommunityCollection existing = collectionMapper.findDefault(userId);
        if (existing != null) {
            return existing;
        }
        CommunityCollection collection = new CommunityCollection();
        collection.setUserId(userId);
        collection.setName("Default inspiration");
        collection.setDefaultCollection(true);
        collection.setItemCount(0L);
        collectionMapper.insertAndReturnId(collection);
        return collectionMapper.findDefault(userId);
    }

    private CommunityCollection requireOwnedCollection(Long collectionId, Long userId) {
        if (collectionId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "collectionId is required");
        }
        CommunityCollection collection = collectionMapper.findOwned(collectionId, userId);
        if (collection == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Collection not found");
        }
        return collection;
    }

    private CommunityCollectionResponse collectionResponse(CommunityCollection collection, Long userId, int itemLimit) {
        List<CommunityPostResponse> items = collection == null ? List.of() : collectionMapper.findItems(collection.getId(), userId, itemLimit, 0)
                .stream()
                .map(post -> response(post, userId))
                .toList();
        return new CommunityCollectionResponse(
                collection.getId(),
                collection.getName(),
                Boolean.TRUE.equals(collection.getDefaultCollection()),
                collection.getItemCount() == null ? 0L : collection.getItemCount(),
                collection.getCreatedAt(),
                collection.getUpdatedAt(),
                items
        );
    }

    private List<CommunityPostResponse> responseBatch(List<? extends CommunityPost> posts, Long viewerId) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        if (posts.get(0) instanceof CommunityPostDiscoverRow) {
            return posts.stream()
                    .map(post -> {
                        CommunityPostDiscoverRow row = (CommunityPostDiscoverRow) post;
                        String authorNickname = row.getAuthorNickname();
                        String authorAvatarUrl = row.getAuthorAvatarUrl();
                        if (authorNickname == null || authorNickname.isBlank()) {
                            User user = row.getUserId() == null ? null : userMapper.selectById(row.getUserId());
                            authorNickname = resolveAuthorNickname(user, row.getUserId());
                            if (authorAvatarUrl == null && user != null) {
                                authorAvatarUrl = user.getAvatarUrl();
                            }
                        } else {
                            authorNickname = normalizeAuthorNickname(authorNickname, row.getUserId());
                        }
                        return buildResponse(row, viewerId, authorNickname, authorAvatarUrl);
                    })
                    .toList();
        }
        List<Long> userIds = posts.stream()
                .map(CommunityPost::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userMapper.findByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left));
        return posts.stream()
                .map(post -> buildResponse(post, viewerId, usersById.get(post.getUserId())))
                .toList();
    }

    private CommunityPostResponse buildResponse(CommunityPost post, Long viewerId, User author) {
        return buildResponse(
                post,
                viewerId,
                resolveAuthorNickname(author, post.getUserId()),
                author == null ? null : author.getAvatarUrl()
        );
    }

    private CommunityPostResponse buildResponse(CommunityPost post,
                                              Long viewerId,
                                              String authorNickname,
                                              String authorAvatarUrl) {
        boolean liked = viewerId != null && postMapper.countLike(post.getId(), viewerId) > 0;
        boolean favorited = viewerId != null && postMapper.countFavorite(post.getId(), viewerId) > 0;
        String promptSnapshot = resolvePromptSnapshot(post);
        return CommunityPostResponse.from(
                post,
                liked,
                favorited,
                postMapper.findTags(post.getId()),
                authorNickname,
                authorAvatarUrl,
                promptSnapshot
        );
    }

    private String resolvePromptSnapshot(CommunityPost post) {
        if (post.getPromptSnapshot() != null && !post.getPromptSnapshot().isBlank()) {
            return post.getPromptSnapshot();
        }
        if (post.getTaskId() == null) {
            return null;
        }
        return taskMapper.findById(post.getTaskId())
                .map(task -> extractPrompt(task.getParamsJson()))
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
    }

    private CommunityPostResponse response(CommunityPost post, Long viewerId) {
        User user = post.getUserId() == null ? null : userMapper.findById(post.getUserId()).orElse(null);
        return buildResponse(post, viewerId, user);
    }

    private CommunityPostResponse response(CommunityPost post, Long viewerId, User author) {
        return buildResponse(post, viewerId, author);
    }

    private String resolveAuthorNickname(User user, Long userId) {
        if (user != null) {
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname().trim();
            }
            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                return user.getUsername().trim();
            }
        }
        return normalizeAuthorNickname(null, userId);
    }

    private String normalizeAuthorNickname(String nickname, Long userId) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }
        if (userId != null) {
            return "用户" + userId;
        }
        return null;
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
        return List.of("LATEST", "POPULAR", "FAVORITES", "SAME_STYLE", "VIEWS", "QUALITY").contains(normalized)
                ? normalized
                : "LATEST";
    }

    private String normalizeAuditStatus(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return List.of("PENDING", "APPROVED", "REJECTED").contains(normalized) ? normalized : null;
    }

    private String normalizeToolCode(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), 128);
    }

    private String normalizeEventType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (List.of("impression", "detail_view", "like", "favorite", "same_style_click",
                "dashboard_open", "task_created", "credit_spent", "share").contains(normalized)) {
            return normalized;
        }
        return "impression";
    }

    private String normalizeCollectionName(String value) {
        if (value == null || value.trim().isBlank()) {
            return "New inspiration";
        }
        return limit(value.trim(), MAX_COLLECTION_NAME_LENGTH);
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

    private String limitNullable(String value, int max) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return limit(value.trim(), max);
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
