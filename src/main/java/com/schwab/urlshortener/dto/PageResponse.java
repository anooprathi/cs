package com.schwab.urlshortener.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable, Spring-Data-independent shape for a paginated REST response.
 * Deliberately not returning Spring Data's Page<T> directly from a
 * controller: that type's own JSON serialization has changed shape
 * across Spring Data versions before, and exposes internal paging
 * metadata (pageable, sort details) callers of this API don't need —
 * this is the intentionally small, stable subset of that information.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
