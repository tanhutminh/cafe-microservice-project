package com.cafe.authservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Schema(example = "your-username", description = "Not a real seeded account - substitute your own credentials") String username,
        @NotBlank @Schema(example = "your-password", description = "Not a real seeded account - substitute your own credentials") String password
) {
}
