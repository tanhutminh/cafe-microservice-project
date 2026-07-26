package com.cafe.menuservice.category.dto;

import jakarta.validation.constraints.NotBlank;

public record CategoryRequest(
        @NotBlank String name,
        int displayOrder,
        boolean active
) {
}
