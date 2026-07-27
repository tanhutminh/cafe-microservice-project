package com.cafe.menuservice.menuitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record MenuItemRequest(
        @NotNull Long categoryId,
        @NotBlank String name,
        String description,
        @NotNull @PositiveOrZero BigDecimal price,
        String imageUrl,
        boolean available,
        boolean active
) {
}
