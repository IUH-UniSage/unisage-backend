package com.unisage.backend.service.category;

import java.util.List;
import java.util.UUID;

import com.unisage.backend.dto.request.CategoryRequest;
import com.unisage.backend.dto.response.CategoryResponse;

public interface CategoryService {

    CategoryResponse createCategory(CategoryRequest request);

    CategoryResponse updateCategory(UUID id, CategoryRequest request);

    CategoryResponse getCategoryById(UUID id);

    List<CategoryResponse> getAllCategory();

    void deleteCategory(UUID id);
}
