package com.cafe.authservice.user.dto;

import com.cafe.authservice.user.Role;
import com.cafe.authservice.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

public record UserResponse(
        @Schema(example = "3") Long id,
        @Schema(example = "cashier2") String username,
        @Schema(example = "Nguyen Van A") String fullName,
        @Schema(example = "CASHIER") Role role,
        @Schema(example = "true") boolean active
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getRole(), user.isActive());
    }
}
