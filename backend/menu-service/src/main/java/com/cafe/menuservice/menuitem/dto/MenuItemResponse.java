package com.cafe.menuservice.menuitem.dto;

import com.cafe.menuservice.menuitem.MenuItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record MenuItemResponse(
        @Schema(example = "12") Long id,
        @Schema(example = "1") Long categoryId,
        @Schema(example = "Coffee") String categoryName,
        @Schema(example = "Cappuccino") String name,
        @Schema(example = "Espresso with steamed milk and a deep layer of foam") String description,
        @Schema(example = "45000") BigDecimal price,
        @Schema(example = "https://example.com/images/cappuccino.jpg") String imageUrl,
        @Schema(example = "true", description = "false means 86'd - hidden from the POS without being deleted") boolean available,
        @Schema(example = "true") boolean active
) {
    public static MenuItemResponse from(MenuItem item) {
        return new MenuItemResponse(
                item.getId(),
                item.getCategory().getId(),
                item.getCategory().getName(),
                item.getName(),
                item.getDescription(),
                item.getPrice(),
                item.getImageUrl(),
                item.isAvailable(),
                item.isActive()
        );
    }
}
