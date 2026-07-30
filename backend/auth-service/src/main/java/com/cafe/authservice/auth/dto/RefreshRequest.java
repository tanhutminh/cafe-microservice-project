package com.cafe.authservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank @Schema(example = "8f14e45f-ceea-467e-bd3a-3b1f8a...") String refreshToken
) {
}
