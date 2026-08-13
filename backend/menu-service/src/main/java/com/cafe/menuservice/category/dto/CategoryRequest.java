package com.cafe.menuservice.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record CategoryRequest(
        @NotBlank @Schema(example = "Coffee") String name,
        @PositiveOrZero @Schema(example = "1", description = "Lower sorts first on the menu") int displayOrder,
        @Schema(example = "true") boolean active
) {
}
