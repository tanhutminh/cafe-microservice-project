package com.cafe.authservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(example = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9...") String accessToken,
        @Schema(example = "8f14e45f-ceea-467e-bd3a-3b1f8a...") String refreshToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "900", description = "Access token lifetime in seconds") int expiresInSeconds
) {
    public static TokenResponse of(String accessToken, String refreshToken, int expiresInSeconds) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
