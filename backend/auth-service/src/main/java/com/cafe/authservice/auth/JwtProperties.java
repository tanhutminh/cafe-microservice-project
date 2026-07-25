package com.cafe.authservice.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String privateKey, int accessTokenTtlMinutes, int refreshTokenTtlDays) {
}
