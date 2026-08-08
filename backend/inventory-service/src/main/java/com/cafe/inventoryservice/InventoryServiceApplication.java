package com.cafe.inventoryservice;

import com.cafe.common.security.HeaderAuthenticationFilter;
import com.cafe.common.security.TrustedHeaderAuth;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@ConfigurationPropertiesScan
@TrustedHeaderAuth
@OpenAPIDefinition(
        info = @Info(
                title = "Inventory Service API",
                version = "v1",
                description = "Ingredients, stock movements, and menu-item recipes. Also consumes the "
                        + "checkout saga's inventory.reserve-stock.command Kafka topic (not exposed via REST). "
                        + "Trusts the X-User-Id/X-Username/X-User-Role headers the gateway sets after "
                        + "verifying a JWT - click Authorize (top right) and fill in the three header "
                        + "values when calling directly (bypassing the gateway) via this Swagger UI, e.g. "
                        + "X-User-Id: 1, X-Username: admin, X-User-Role: ADMIN."
        ),
        security = {
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ID),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USERNAME),
                @SecurityRequirement(name = HeaderAuthenticationFilter.HEADER_USER_ROLE)
        }
)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
