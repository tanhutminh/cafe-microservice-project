package com.cafe.authservice.auth;

import com.cafe.authservice.user.User;
import com.cafe.common.security.PemKeyUtils;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.springframework.stereotype.Service;

/**
 * Signs access tokens with the auth-service-owned RS256 private key, supplied via the
 * APP_JWT_PRIVATE_KEY env var (docker-compose locally, a K8s Secret synced from GCP Secret Manager
 * in the real deployment). Only the matching public key ever leaves this service, supplied to
 * gateway the same way via its own APP_JWT_PUBLIC_KEY env var.
 */
@Service
public class JwtService {

  private final JwtProperties jwtProperties;
  private PrivateKey privateKey;

  public JwtService(JwtProperties jwtProperties) {
    this.jwtProperties = jwtProperties;
  }

  @PostConstruct
  void init() {
    this.privateKey = PemKeyUtils.parsePrivateKey(jwtProperties.privateKey());
  }

  public String generateAccessToken(User user) {
    Instant now = Instant.now();
    Instant expiry = now.plus(Duration.ofMinutes(jwtProperties.accessTokenTtlMinutes()));

    return Jwts.builder()
        .subject(user.getUsername())
        .claim("userId", user.getId())
        .claim("role", user.getRole().name())
        .issuedAt(Date.from(now))
        .expiration(Date.from(expiry))
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  public int accessTokenTtlSeconds() {
    return jwtProperties.accessTokenTtlMinutes() * 60;
  }
}
