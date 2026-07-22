package com.aiminilab.aitoolmarket.learning.service;

import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CategoryRequest;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CategoryResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.CoverUploadResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.PublicCategoryResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.PublicLearningCenterResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.PublicTutorialResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.TeacherContactResponse;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.TutorialRequest;
import com.aiminilab.aitoolmarket.learning.dto.LearningCenterDtos.TutorialResponse;
import com.aiminilab.aitoolmarket.learning.entity.LearningCategory;
import com.aiminilab.aitoolmarket.learning.entity.LearningTutorial;
import com.aiminilab.aitoolmarket.learning.mapper.LearningCategoryMapper;
import com.aiminilab.aitoolmarket.learning.mapper.LearningTutorialMapper;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LearningCenterService {
    private static final long MAX_COVER_BYTES = 5L * 1024 * 1024;
    private static final Set<String> COVER_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> COVER_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");
    private static final DateTimeFormatter FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final LearningCategoryMapper categoryMapper;
    private final LearningTutorialMapper tutorialMapper;
    private final SystemSettingService systemSettingService;
    private final AssetStorageService assetStorageService;

    public LearningCenterService(LearningCategoryMapper categoryMapper,
                                 LearningTutorialMapper tutorialMapper,
                                 SystemSettingService systemSettingService,
                                 AssetStorageService assetStorageService) {
        this.categoryMapper = categoryMapper;
        this.tutorialMapper = tutorialMapper;
        this.systemSettingService = systemSettingService;
        this.assetStorageService = assetStorageService;
    }

    public List<CategoryResponse> adminCategories() {
        return categoryMapper.findAll().stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        LearningCategory category = apply(new LearningCategory(), request);
        categoryMapper.insert(category);
        return CategoryResponse.from(requireCategory(category.getId()));
    }

    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest request) {
        requireCategory(id);
        LearningCategory category = apply(new LearningCategory(), request);
        category.setId(id);
        categoryMapper.update(category);
        return CategoryResponse.from(requireCategory(id));
    }

    @Transactional
    public void deleteCategory(Long id) {
        requireCategory(id);
        if (tutorialMapper.countByCategoryId(id) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该分类下仍有教程，请先移动或删除教程");
        }
        categoryMapper.deleteById(id);
    }

    public List<TutorialResponse> adminTutorials(Long categoryId) {
        if (categoryId != null) requireCategory(categoryId);
        return tutorialMapper.findAll(categoryId).stream().map(TutorialResponse::from).toList();
    }

    @Transactional
    public TutorialResponse createTutorial(TutorialRequest request) {
        requireCategory(request.categoryId());
        LearningTutorial tutorial = apply(new LearningTutorial(), request);
        tutorialMapper.insert(tutorial);
        return TutorialResponse.from(requireTutorial(tutorial.getId()));
    }

    @Transactional
    public TutorialResponse updateTutorial(Long id, TutorialRequest request) {
        requireTutorial(id);
        requireCategory(request.categoryId());
        LearningTutorial tutorial = apply(new LearningTutorial(), request);
        tutorial.setId(id);
        tutorialMapper.update(tutorial);
        return TutorialResponse.from(requireTutorial(id));
    }

    @Transactional
    public void deleteTutorial(Long id) {
        requireTutorial(id);
        tutorialMapper.deleteById(id);
    }

    public PublicLearningCenterResponse publicLearningCenter() {
        List<LearningCategory> categories = categoryMapper.findEnabled();
        Map<Long, List<PublicTutorialResponse>> tutorials = new LinkedHashMap<>();
        for (LearningTutorial tutorial : tutorialMapper.findVisible()) {
            tutorials.computeIfAbsent(tutorial.getCategoryId(), ignored -> new java.util.ArrayList<>())
                    .add(PublicTutorialResponse.from(tutorial));
        }
        List<PublicCategoryResponse> result = categories.stream()
                .map(category -> new PublicCategoryResponse(category.getId(), category.getName(),
                        tutorials.getOrDefault(category.getId(), List.of())))
                .toList();
        return new PublicLearningCenterResponse(result,
                TeacherContactResponse.from(systemSettingService.publicCustomerServiceSettings()));
    }

    public CoverUploadResponse uploadCover(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的教程封面");
        }
        if (file.getSize() > MAX_COVER_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED, "教程封面不能超过 5MB");
        }
        String contentType = normalize(file.getContentType()).toLowerCase(Locale.ROOT);
        String extension = resolveExtension(file.getOriginalFilename());
        if (!COVER_EXTENSIONS.contains(extension) || !COVER_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "仅支持 JPG、PNG、WebP、GIF 图片");
        }
        if ("jpeg".equals(extension)) extension = "jpg";
        String filename = "tutorial-" + LocalDateTime.now().format(FILENAME_TIME) + "-"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        StoredAsset stored = assetStorageService.storeMultipartPublicUnique("learning-center/covers/" + filename, file);
        return new CoverUploadResponse(stored.publicUrl(), filename,
                file.getContentType() == null ? "" : file.getContentType(), file.getSize());
    }

    private LearningCategory apply(LearningCategory category, CategoryRequest request) {
        category.setName(request.name().trim());
        category.setSortOrder(request.sortOrder());
        category.setEnabled(request.enabled());
        return category;
    }

    private LearningTutorial apply(LearningTutorial tutorial, TutorialRequest request) {
        validateHttpUrl(request.videoUrl(), "视频地址");
        String cover = normalize(request.coverImageUrl());
        if (!cover.isBlank()) validateHttpUrl(cover, "封面地址");
        tutorial.setCategoryId(request.categoryId());
        tutorial.setTitle(request.title().trim());
        tutorial.setSummary(normalize(request.summary()));
        tutorial.setCoverImageUrl(cover);
        tutorial.setVideoUrl(request.videoUrl().trim());
        tutorial.setSortOrder(request.sortOrder());
        tutorial.setEnabled(request.enabled());
        return tutorial;
    }

    private LearningCategory requireCategory(Long id) {
        LearningCategory value = id == null ? null : categoryMapper.findById(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "学习分类不存在");
        return value;
    }

    private LearningTutorial requireTutorial(Long id) {
        LearningTutorial value = id == null ? null : tutorialMapper.findById(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "视频教程不存在");
        return value;
    }

    private void validateHttpUrl(String raw, String label) {
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, label + "必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private String resolveExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (COVER_EXTENSIONS.contains(ext)) return ext;
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
