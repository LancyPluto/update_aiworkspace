package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.ppt.entity.PptProjectBinding;

import java.time.LocalDateTime;

public record PptProjectSummaryResponse(
        Long bindingId,
        String title,
        String status,
        String creationType,
        Integer pageCount,
        LocalDateTime updatedAt
) {
    public static PptProjectSummaryResponse from(PptProjectBinding binding, Integer pageCount) {
        return new PptProjectSummaryResponse(
                binding.getId(),
                binding.getTitle(),
                binding.getStatus(),
                binding.getCreationType(),
                pageCount,
                binding.getUpdatedAt()
        );
    }
}
