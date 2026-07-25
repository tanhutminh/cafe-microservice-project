package com.cafe.authservice.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresInSeconds
) {
    public static TokenResponse of(String accessToken, String refreshToken, int expiresInSeconds) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
