package com.cafe.authservice.auth;

import com.cafe.authservice.user.User;
import com.cafe.common.security.PemKeyUtils;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Signs access tokens with the auth-service-owned RS256 private key (distributed via
 * config-server). Only the matching public key ever leaves this service, via gateway.yml.
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
