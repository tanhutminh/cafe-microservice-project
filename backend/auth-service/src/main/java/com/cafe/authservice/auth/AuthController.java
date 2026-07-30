package com.cafe.authservice.auth;

import com.cafe.authservice.auth.dto.LoginRequest;
import com.cafe.authservice.auth.dto.RefreshRequest;
import com.cafe.authservice.auth.dto.TokenResponse;
import com.cafe.authservice.user.UserService;
import com.cafe.authservice.user.dto.UserResponse;
import com.cafe.common.security.HeaderAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Login, token refresh/logout, and \"who am I\"")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username/password for an access + refresh token pair (public)")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a still-valid refresh token for a new token pair (public, rotates the refresh token)")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token, ending that session")
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID)
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME)
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user's profile")
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID)
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME)
    @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
    public UserResponse me(Authentication authentication) {
        return UserResponse.from(userService.findByUsername(authentication.getName()));
    }
}
