package com.cafe.authservice;

import com.cafe.common.security.TrustedHeaderAuth;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * No document-wide {@code security} default here (unlike the other domain services) -
 * auth-service is the one service where most, but not all, endpoints need the trusted
 * headers (login/refresh are genuinely public), and swagger-core's @Operation.security()
 * can't distinguish "explicitly none" from "not specified" (both serialize as the
 * annotation's default, an empty array) - so each protected endpoint declares its own
 * @SecurityRequirement trio directly instead of relying on an inheritable default to override.
 */
@SpringBootApplication
@EnableDiscoveryClient
@ConfigurationPropertiesScan
@TrustedHeaderAuth
@OpenAPIDefinition(info = @Info(
        title = "Auth Service API",
        version = "v1",
        description = "Login, refresh, logout, and user management. Endpoints under /api/users/** "
                + "require ADMIN. Only POST /api/auth/login and POST /api/auth/refresh are public - "
                + "they're how identity gets established in the first place (no Authorize needed for "
                + "those two). Every other endpoint (including GET /api/auth/me) trusts the "
                + "X-User-Id/X-Username/X-User-Role headers the gateway sets after verifying a JWT, "
                + "same as the other domain services; click Authorize (top right) and fill in the "
                + "three header values when calling directly (bypassing the gateway) via this "
                + "Swagger UI, e.g. X-User-Id: 1, X-Username: admin, X-User-Role: ADMIN."
))
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
