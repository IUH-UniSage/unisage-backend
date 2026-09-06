package com.unisage.backend.service.category;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.unisage.backend.dto.request.CategoryRequest;
import com.unisage.backend.dto.response.CategoryResponse;
import com.unisage.backend.entity.Category;
import com.unisage.backend.exception.AppException;
import com.unisage.backend.exception.ErrorCode;
import com.unisage.backend.repository.CategoryRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        if (categoryRepository.existsByName(request.name())) {
            throw new AppException(ErrorCode.CATEGORY_NAME_EXISTED);
        }

        Category category = Category.builder()
                .name(request.name())
                .description(request.description())
                .status(request.status())
                .build();
        category = categoryRepository.save(category);
        return mapToResponse(category);
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(UUID id, CategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        if (request.name() != null && !request.name().equals(category.getName())) {
            if (categoryRepository.existsByName(request.name())) {
                throw new AppException(ErrorCode.CATEGORY_NAME_EXISTED);
            }
            category.setName(request.name());
        }
        category.setDescription(request.description());
        category.setStatus(request.status());

        category = categoryRepository.save(category);
        return mapToResponse(category);
    }

    @Override
    public CategoryResponse getCategoryById(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
        return mapToResponse(category);
    }

    @Override
    public List<CategoryResponse> getAllCategory() {
        return categoryRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteCategory(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));
        category.setIsActive(false);
        categoryRepository.save(category);
    }

    private CategoryResponse mapToResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .status(category.getStatus())
                .description(category.getDescription())
                .isActive(category.getIsActive())
                .createdAt(category.getCreatedAt())
                .createdBy(category.getCreatedBy() != null ? category.getCreatedBy().getId().toString() : null)
                .createdByName(category.getCreatedBy() != null ? category.getCreatedBy().getFullName() : null)
                .updatedAt(category.getUpdatedAt())
                .updatedBy(category.getUpdatedBy() != null ? category.getUpdatedBy().getId().toString() : null)
                .updatedByName(category.getUpdatedBy() != null ? category.getUpdatedBy().getFullName() : null)
                .build();
    }
}
