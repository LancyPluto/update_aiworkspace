package com.aiminilab.aitoolmarket.common.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> list,
        long total,
        int pageNo,
        int pageSize,
        boolean hasNext
) {
    private static final int DEFAULT_PAGE_NO = 1;

    public PageResponse(List<T> list, long total) {
        this(list, total, DEFAULT_PAGE_NO, list == null ? 0 : list.size(), false);
    }

    public static <T> PageResponse<T> of(List<T> list, long total, Integer pageNo, Integer pageSize) {
        int normalizedPageNo = pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : pageNo;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        boolean hasNext = (long) normalizedPageNo * normalizedPageSize < total;
        return new PageResponse<>(list, total, normalizedPageNo, normalizedPageSize, hasNext);
    }

    public static int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : pageNo;
    }

    public static int normalizePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    public static int offset(Integer pageNo, Integer pageSize) {
        return (normalizePageNo(pageNo) - 1) * normalizePageSize(pageSize);
    }
}
