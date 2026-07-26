package com.unisage.backend.dto.response;

import lombok.Builder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.Function;

@Builder
public record PageResponse<T>(
        T data,
        int page,
        int totalPages,
        int limit,
        long totalItems) {
    public static <E, R> PageResponse<List<R>> fromPage(Page<E> page, Function<E, R> mapper) {
        return fromPageData(page, page.getContent().stream().map(mapper).toList());
    }

    public static <E, R> PageResponse<List<R>> fromPageData(Page<E> page, List<R> data) {
        return PageResponse.<List<R>>builder()
                .data(data)
                .page(page.getNumber() + 1)
                .totalPages(page.getTotalPages())
                .limit(page.getSize())
                .totalItems(page.getTotalElements())
                .build();
    }

    public static <T> PageResponse<List<T>> empty(Pageable pageable) {
        return PageResponse.<List<T>>builder()
                .data(List.of())
                .page(pageable.getPageNumber())
                .totalPages(0)
                .limit(0)
                .totalItems(0)
                .build();
    }
}
