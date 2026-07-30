package com.cafe.menuservice;

import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.common.security.TrustedHeaderAuth;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
@TrustedHeaderAuth
@OpenAPIDefinition(
        info = @Info(
                title = "Menu Service API",
                version = "v1",
                description = "Categories and menu items. This service trusts the X-User-Id/X-Username/"
                        + "X-User-Role headers the gateway sets after verifying a JWT (plan section 5) - it "
                        + "never sees the token itself. When calling directly (bypassing the gateway) via "
                        + "this Swagger UI, click Authorize (top right) and fill in the three header "
                        + "values, e.g. X-User-Id: 1, X-Username: admin, X-User-Role: ADMIN."
        ),
        security = {
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
        }
)
public class MenuServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MenuServiceApplication.class, args);
    }
}
