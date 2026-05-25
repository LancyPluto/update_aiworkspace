package com.aiminilab.aitoolmarket.ppt.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePptProjectRequest(
        @NotBlank String creationType,
        /**
         * 创建项目时定位 PPT 工具实例；缺省时由后端兜底使用历史唯一的 PPT 工具
         * （仅作迁移期兼容，目标是必填）。
         */
        String toolCode,
        String ideaPrompt,
        String outlineText,
        String descriptionText,
        String templateStyle,
        String imageAspectRatio,
        String title,
        String clientRequestId
) {
}
