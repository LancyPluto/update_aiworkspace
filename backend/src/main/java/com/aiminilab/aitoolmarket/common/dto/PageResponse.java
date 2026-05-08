package com.aiminilab.aitoolmarket.common.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> list,
        long total
) {
}
