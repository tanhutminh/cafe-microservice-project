package com.cafe.menuservice.category.dto;

import com.cafe.menuservice.category.Category;

public record CategoryResponse(Long id, String name, int displayOrder, boolean active) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDisplayOrder(), category.isActive());
    }
}
