package com.cafe.authservice.user.dto;

import com.cafe.authservice.user.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 50) @Schema(example = "cashier2") String username,
        @NotBlank @Size(min = 8, max = 100) @Schema(example = "a-strong-password") String password,
        @NotBlank @Size(max = 150) @Schema(example = "Nguyen Van A") String fullName,
        @NotNull @Schema(example = "CASHIER") Role role
) {
}
