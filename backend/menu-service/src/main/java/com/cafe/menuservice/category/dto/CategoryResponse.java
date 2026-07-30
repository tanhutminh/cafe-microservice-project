package com.cafe.menuservice.category.dto;

import com.cafe.menuservice.category.Category;
import io.swagger.v3.oas.annotations.media.Schema;

public record CategoryResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "Coffee") String name,
        @Schema(example = "1") int displayOrder,
        @Schema(example = "true") boolean active
) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getDisplayOrder(), category.isActive());
    }
}
