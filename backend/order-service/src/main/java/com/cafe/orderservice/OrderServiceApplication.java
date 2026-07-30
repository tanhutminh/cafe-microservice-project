package com.cafe.orderservice;

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
                title = "Order Service API",
                version = "v1",
                description = "Dining tables, orders/POS, and the checkout saga orchestrator. Trusts the "
                        + "X-User-Id/X-Username/X-User-Role headers the gateway sets after verifying a JWT - "
                        + "click Authorize (top right) and fill in the three header values when calling "
                        + "directly (bypassing the gateway) via this Swagger UI, e.g. X-User-Id: 1, "
                        + "X-Username: admin, X-User-Role: ADMIN. POST /api/orders/{id}/checkout returns "
                        + "202 immediately - the order settles to PAID or back to OPEN asynchronously once "
                        + "the saga completes; poll GET /api/orders/{id} to observe the transition."
        ),
        security = {
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
        }
)
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
