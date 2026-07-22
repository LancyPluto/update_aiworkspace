package com.aiminilab.aitoolmarket.learning.dto;

import com.aiminilab.aitoolmarket.admin.dto.CustomerServiceSettingsResponse;
import com.aiminilab.aitoolmarket.learning.entity.LearningCategory;
import com.aiminilab.aitoolmarket.learning.entity.LearningTutorial;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class LearningCenterDtos {
    private LearningCenterDtos() {}

    public record CategoryRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean enabled
    ) {}

    public record TutorialRequest(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 500) String summary,
            @Size(max = 1024) String coverImageUrl,
            @NotBlank @Size(max = 2048) String videoUrl,
            @NotNull @Min(0) @Max(9999) Integer sortOrder,
            @NotNull Boolean enabled
    ) {}

    public record CategoryResponse(
            Long id,
            String name,
            int sortOrder,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static CategoryResponse from(LearningCategory value) {
            return new CategoryResponse(value.getId(), value.getName(), value.getSortOrder(),
                    Boolean.TRUE.equals(value.getEnabled()), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record TutorialResponse(
            Long id,
            Long categoryId,
            String title,
            String summary,
            String coverImageUrl,
            String videoUrl,
            int sortOrder,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static TutorialResponse from(LearningTutorial value) {
            return new TutorialResponse(value.getId(), value.getCategoryId(), value.getTitle(), value.getSummary(),
                    value.getCoverImageUrl(), value.getVideoUrl(), value.getSortOrder(),
                    Boolean.TRUE.equals(value.getEnabled()), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record PublicCategoryResponse(Long id, String name, List<PublicTutorialResponse> tutorials) {}

    public record PublicTutorialResponse(
            Long id,
            String title,
            String summary,
            String coverImageUrl,
            String videoUrl
    ) {
        public static PublicTutorialResponse from(LearningTutorial value) {
            return new PublicTutorialResponse(value.getId(), value.getTitle(), value.getSummary(),
                    value.getCoverImageUrl(), value.getVideoUrl());
        }
    }

    public record TeacherContactResponse(boolean enabled, String description, String qrCodeUrl) {
        public static TeacherContactResponse from(CustomerServiceSettingsResponse value) {
            return new TeacherContactResponse(value.enabled(), value.description(), value.qrCodeUrl());
        }
    }

    public record PublicLearningCenterResponse(
            List<PublicCategoryResponse> categories,
            TeacherContactResponse teacherContact
    ) {}

    public record CoverUploadResponse(String url, String filename, String contentType, long fileSize) {}
}
