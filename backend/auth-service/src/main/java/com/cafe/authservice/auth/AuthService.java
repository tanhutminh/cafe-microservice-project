package com.cafe.authservice.auth;

import com.cafe.authservice.auth.dto.TokenResponse;
import com.cafe.authservice.user.User;
import com.cafe.authservice.user.UserService;
import com.cafe.common.exception.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder,
                        JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public TokenResponse login(String username, String rawPassword) {
        User user;
        try {
            user = userService.findByUsername(username);
        } catch (RuntimeException e) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        if (!user.isActive() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user.getId());
        return TokenResponse.of(accessToken, refreshToken, jwtService.accessTokenTtlSeconds());
    }

    public TokenResponse refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedToken rotated = refreshTokenService.rotate(rawRefreshToken);
        User user = userService.findById(rotated.userId());
        String accessToken = jwtService.generateAccessToken(user);
        return TokenResponse.of(accessToken, rotated.rawToken(), jwtService.accessTokenTtlSeconds());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }
}
