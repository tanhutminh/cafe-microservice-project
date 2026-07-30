package com.cafe.menuservice.menuitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record MenuItemRequest(
        @NotNull @Schema(example = "1") Long categoryId,
        @NotBlank @Schema(example = "Cappuccino") String name,
        @Schema(example = "Espresso with steamed milk and a deep layer of foam") String description,
        @NotNull @PositiveOrZero @Schema(example = "45000") BigDecimal price,
        @Schema(example = "https://example.com/images/cappuccino.jpg") String imageUrl,
        @Schema(example = "true") boolean available,
        @Schema(example = "true") boolean active
) {
}
